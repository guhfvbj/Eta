package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.mcp.McpDeferredGroup
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.CancellationException
import org.json.JSONArray

/** 管理可替换的模型上下文；持久快照先提交，运行 transcript 始终追加。 */
internal class AgentContextSession(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    systemCount: Int,
    private val operationId: String,
    private val provider: AgentProviderClient,
    private val runController: AgentRunController,
    private val sensitiveIds: () -> Set<String>,
    private val onEvent: (AgentEvent) -> Unit,
    private val onContextSnapshot: (AgentContextSnapshot) -> Unit,
    private val transcriptSize: () -> Int = { 0 },
    private val roleplay: Boolean = false,
    private val mainToolsProvider: () -> JSONArray = { JSONArray() },
    private val mainSessionId: String = operationId,
    private val messageProjector: ((JSONArray) -> JSONArray)? = null,
    private val enableCacheReuse: Boolean = false,
    private val systemCountProvider: () -> Int = { systemCount },
    private val dynamicMcpGroupsProvider: () -> List<McpDeferredGroup> = { emptyList() },
    initialTokenBaseline: AgentContextTokenBaseline? = null,
    private val requestProjection: (JSONArray) -> JSONArray = AssistantScreenContextProjection::project,
) {
    private val systemCount: Int get() = systemCountProvider()
    private val contextWindow = config.knownContextWindow
    private var baseline = initialTokenBaseline
    private var tools = JSONArray()
    private var requestBaseline: AgentContextTokenBaseline? = null
    private var requestLocalTokens: Int? = null
    private var noProgressCount = initialTokenBaseline?.compactionNoProgressCount?.coerceIn(0, 8) ?: 0
    private var cooldownRemaining = initialTokenBaseline?.compactionCooldownRemaining?.coerceIn(0, 64) ?: 0
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null
    private var committedEncoding: String? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun prepareRequest(roundTools: JSONArray) {
        tools = roundTools
        requestBaseline = null
        requestLocalTokens = null
        val previous = tokenBaseline(messages)
        val folded = AgentToolOutputFolder.fold(messages, systemCount)
        if (folded.changedMessages > 0) {
            val history = durableHistory(messages)
            // 折叠仅减去已知改动的本地成本，不丢弃服务端 usage 锚点。
            baseline = previous.copy(
                lastContextTokens = (previous.lastContextTokens - folded.savedTokens).coerceAtLeast(0),
                messageCount = history.size,
                fingerprint = AgentTokenEstimator.fingerprint(history),
            )
        }
    }

    fun captureRequest() {
        val captured = tokenBaseline(messages)
        requestBaseline = captured
        requestLocalTokens = AgentTokenEstimator.bounded(captured.prefixTokens.toLong() +
            AgentTokenEstimator.messages(durableHistory(messages)))
    }

    fun effectiveTokens(): Int = tokenBaseline(messages).lastContextTokens

    /** Called only after a complete, validated provider round, never for failed attempts. */
    fun observeInputTokens(tokens: Int?) {
        if (tokens == null || tokens < 0) return
        val captured = requestBaseline ?: tokenBaseline(messages)
        val local = requestLocalTokens ?: AgentTokenEstimator.bounded(captured.prefixTokens.toLong() +
            AgentTokenEstimator.messages(durableHistory(messages)))
        val ratio = tokens.toDouble() / local.coerceAtLeast(1)
        // 旧版本系数的观测值不可比，直接采用本次真实比例，避免升级后先低估一轮。
        val scale = (if (captured.estimatorVersion >= AgentTokenEstimator.VERSION) {
            (captured.boundedEstimateScale + ratio) / 2
        } else ratio).coerceIn(0.1, 2.0)
        baseline = captured.copy(lastContextTokens = tokens, estimateScale = scale)
    }

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    /** Also used on cancellation/failure: never invoke the provider or check cancellation here. */
    fun checkpoint() {
        val snapshot = createSnapshot(messages)
        val encoded = snapshot.encode()
        // Keep a safe terminal snapshot even if durable I/O fails. Compaction itself remains atomic.
        committedSnapshot = snapshot
        saveContext(snapshot, encoded)
    }

    private fun saveContext(snapshot: AgentContextSnapshot, encoded: String) {
        if (encoded == committedEncoding) return
        onContextSnapshot(snapshot)
        // Failed persistence must remain retryable, even when the in-memory snapshot is current.
        committedEncoding = encoded
    }

    private fun tokenBaseline(candidate: JSONArray): AgentContextTokenBaseline {
        val history = durableHistory(candidate)
        val localHistory = AgentTokenEstimator.messages(history)
        val projected = requestProjection(candidate)
        val localRequest = AgentTokenEstimator.messages(projected)
        val prefix = AgentTokenEstimator.bounded((localRequest.toLong() - localHistory).coerceAtLeast(0) +
            AgentTokenEstimator.json(tools))
        val known = baseline?.takeIf { it.matches(history, config) }
        // 估算权重、模型或窗口变更后旧校准系数不再适用，从 1.0 重新学习。
        val scale = baseline?.takeIf { it.estimatorVersion >= AgentTokenEstimator.VERSION &&
            it.modelKey == AgentTokenEstimator.modelKey(config) }
            ?.boundedEstimateScale ?: 1.0
        // 老库的 prefixTokens 使用双倍中文权重，不能把估算算法差异误当作提示词删除。
        val comparablePrefix = if (known != null && known.estimatorVersion < AgentTokenEstimator.VERSION) {
            AgentTokenEstimator.bounded((AgentTokenEstimator.messages(projected, legacy = true).toLong() -
                AgentTokenEstimator.messages(history, legacy = true)).coerceAtLeast(0) + AgentTokenEstimator.json(tools, legacy = true))
        } else prefix
        val tokens = if (known == null) ((prefix.toLong() + localHistory) * scale).toLong() else {
            known.lastContextTokens.toLong() + (comparablePrefix - known.prefixTokens) +
                AgentTokenEstimator.messages(history.drop(known.messageCount))
        }
        return AgentContextTokenBaseline(AgentTokenEstimator.bounded(tokens), history.size,
            AgentTokenEstimator.fingerprint(history), prefix, AgentTokenEstimator.modelKey(config),
            scale, noProgressCount, cooldownRemaining, estimatorVersion = AgentTokenEstimator.VERSION)
    }

    private fun createSnapshot(candidate: JSONArray): AgentContextSnapshot {
        val history = AgentToolBatchRecovery.completeInterrupted(durableHistory(candidate))
        val tokens = tokenBaseline(candidate)
        return AgentContextSnapshot(
            operationId = operationId,
            messages = history,
            coveredUserTurns = history.sumOf { it.compactedUserTurns },
            consumedUserTurns = consumedUserTurns,
            consumedSupplementCount = consumedSupplementCount,
            consumedTranscriptMessages = transcriptSize(),
            // Interrupted tool closures are synthetic; include their cost in the saved baseline.
            tokenBaseline = tokens.copy(messageCount = history.size,
                fingerprint = AgentTokenEstimator.fingerprint(history),
                lastContextTokens = AgentTokenEstimator.bounded(tokens.lastContextTokens.toLong() +
                    (AgentTokenEstimator.messages(history) - AgentTokenEstimator.messages(durableHistory(candidate))).coerceAtLeast(0))),
        )
    }

    fun compact(force: Boolean = false, final: Boolean = false, emergency: Boolean = false) {
        val before = effectiveTokens()
        if (SignedToolContext.hasPendingResponse(messages)) {
            if (force) throw SignedToolContext.compactionFailure(messages)
            checkpoint()
            return
        }
        if (!force && (!config.autoCompactionEnabled || contextWindow == null || before < contextWindow * TRIGGER_RATIO)) {
            checkpoint()
            return
        }
        if (!force && cooldownRemaining > 0) {
            cooldownRemaining--
            checkpoint()
            return
        }
        val operation = java.util.UUID.randomUUID().toString()
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, before))
        try {
            val compactor = AgentContextCompactor(config, provider, runController, roleplay = roleplay,
                dynamicMcpGroupsProvider = dynamicMcpGroupsProvider,
                mainTools = mainToolsProvider(), mainSessionId = mainSessionId,
                messageProjector = messageProjector, enableCacheReuse = enableCacheReuse)
            val outcome = try {
                compactor.compactWithOutcome(messages, systemCount, sensitiveIds())
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!emergency || failure is AgentRunCancelledException || failure is CancellationException ||
                    failure is InterruptedException || Thread.currentThread().isInterrupted) throw failure
                compactor.fallbackCompactWithOutcome(messages, systemCount, sensitiveIds())
            }
            runController.throwIfCancelled()
            val candidate = outcome.messages
            val previousBaseline = baseline
            val previousNoProgress = noProgressCount
            val previousCooldown = cooldownRemaining
            // 压缩改变前缀，仅失效指纹；保留 usage 学到的校准系数。
            baseline = baseline?.copy(messageCount = -1)
            if (tokenBaseline(candidate).lastContextTokens > before * 0.95) recordNoProgress() else {
                noProgressCount = 0
                cooldownRemaining = 0
            }
            val snapshot = try {
                createSnapshot(candidate).also {
                    saveContext(it, it.encode())
                }
            } catch (failure: Exception) {
                baseline = previousBaseline
                noProgressCount = previousNoProgress
                cooldownRemaining = previousCooldown
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            baseline = snapshot.tokenBaseline
            committedSnapshot = snapshot
            onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_COMPLETED, before,
                usage = outcome.usage, archiveId = outcome.archiveId))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (failure is AgentModelFailure && failure.code in setOf("CONTEXT_NO_REDUCTION", "CONTEXT_NOT_COMPACTABLE")) {
                recordNoProgress()
                checkpoint()
                if (!force) return
            }
            if (!final) throw failure
            committedSnapshot = createSnapshot(messages)
        }
    }

    private fun recordNoProgress() {
        noProgressCount = (noProgressCount + 1).coerceAtMost(8)
        cooldownRemaining = (noProgressCount * 8).coerceAtMost(64)
    }

    private fun durableHistory(source: JSONArray): List<AgentModelClient.ConversationMessage> {
        val durable = JSONArray()
        for (index in systemCount until source.length()) {
            val message = source.getJSONObject(index)
            if (!message.optBoolean("_eta_observation")) durable.put(message)
        }
        return AgentConversationCodec.transcript(durable, 0, sensitiveIds())
    }

    private companion object {
        // 300K 窗口在 255K 触发；冷却不保证不溢出，硬溢出仍走 force/emergency 恢复。
        const val TRIGGER_RATIO = 0.85
    }
}
