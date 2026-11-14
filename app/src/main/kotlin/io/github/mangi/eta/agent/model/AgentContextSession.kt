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
    private var consumedSupplementCount = 0
    private var consumedUserTurns = (systemCount until messages.length()).sumOf {
        val message = messages.getJSONObject(it)
        message.optInt("_eta_compacted_users") + if (message.optString("role") == "user") 1 else 0
    }
    private var committedSnapshot: AgentContextSnapshot? = null

    fun snapshot(): AgentContextSnapshot? = committedSnapshot

    fun prepareRequest(roundTools: JSONArray) {
        tools = roundTools
        requestBaseline = null
        if (AgentToolOutputFolder.fold(messages, systemCount) > 0) {
            // Observed usage described a different prefix; estimate the folded request
            // locally until a successful response establishes its new exact baseline.
            baseline = null
        }
    }

    fun captureRequest() { requestBaseline = tokenBaseline(messages) }

    fun effectiveTokens(): Int = tokenBaseline(messages).lastContextTokens

    /** Called only after a complete, validated provider round, never for failed attempts. */
    fun observeInputTokens(tokens: Int?) {
        if (tokens == null || tokens < 0) return
        baseline = (requestBaseline ?: tokenBaseline(messages)).copy(lastContextTokens = tokens)
    }

    fun userAppended() {
        consumedUserTurns++
        consumedSupplementCount++
    }

    /** Also used on cancellation/failure: never invoke the provider or check cancellation here. */
    fun checkpoint() {
        val snapshot = createSnapshot(messages)
        snapshot.encode()
        // Keep a safe terminal snapshot even if durable I/O fails. Compaction itself remains atomic.
        committedSnapshot = snapshot
        onContextSnapshot(snapshot)
    }

    private fun tokenBaseline(candidate: JSONArray): AgentContextTokenBaseline {
        val history = durableHistory(candidate)
        val localHistory = AgentTokenEstimator.messages(history)
        val localRequest = AgentTokenEstimator.messages(requestProjection(candidate))
        val prefix = AgentTokenEstimator.bounded((localRequest.toLong() - localHistory).coerceAtLeast(0) +
            AgentTokenEstimator.json(tools))
        val known = baseline?.takeIf { it.matches(history, config) }
        val tokens = if (known == null) prefix.toLong() + localHistory else {
            known.lastContextTokens.toLong() + (prefix - known.prefixTokens) +
                AgentTokenEstimator.messages(history.drop(known.messageCount))
        }
        return AgentContextTokenBaseline(AgentTokenEstimator.bounded(tokens), history.size,
            AgentTokenEstimator.fingerprint(history), prefix, AgentTokenEstimator.modelKey(config))
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
        val operation = java.util.UUID.randomUUID().toString()
        onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_STARTED, before))
        try {
            val compactor = AgentContextCompactor(config, provider, runController, roleplay = roleplay,
                dynamicMcpGroupsProvider = dynamicMcpGroupsProvider)
            val candidate = try {
                compactor.compact(messages, systemCount, sensitiveIds())
            } catch (failure: Exception) {
                runController.throwIfCancelled()
                if (!emergency || failure is AgentRunCancelledException || failure is CancellationException ||
                    failure is InterruptedException || Thread.currentThread().isInterrupted) throw failure
                compactor.fallbackCompact(messages, systemCount, sensitiveIds())
            }
            runController.throwIfCancelled()
            val previousBaseline = baseline
            baseline = null // A changed prefix invalidates observed usage.
            val snapshot = try {
                createSnapshot(candidate).also {
                    it.encode()
                    onContextSnapshot(it)
                }
            } catch (failure: Exception) {
                baseline = previousBaseline
                throw failure
            }
            while (messages.length() > 0) messages.remove(messages.length() - 1)
            for (index in 0 until candidate.length()) messages.put(candidate.getJSONObject(index))
            baseline = snapshot.tokenBaseline
            committedSnapshot = snapshot
            onEvent(AgentEvent.ContextCompaction(operation, AgentEvent.ContextCompaction.PHASE_COMPLETED, before))
        } catch (failure: Exception) {
            runController.throwIfCancelled()
            onEvent(AgentEvent.ContextCompaction(operation, "failed", before,
                reasonCode = (failure as? AgentModelFailure)?.code ?: "CONTEXT_SUMMARY_FAILED"))
            if (!final) throw failure
            committedSnapshot = createSnapshot(messages)
        }
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
        const val TRIGGER_RATIO = 0.75
    }
}
