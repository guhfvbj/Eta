package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.mcp.McpDeferredGroup
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** 只在完整工具交换之间生成候选摘要；全部验证通过后由会话一次性提交。 */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
    private val mainTools: JSONArray? = null,
    private val mainSessionId: String? = null,
    private val messageProjector: ((JSONArray) -> JSONArray)? = null,
    private val enableCacheReuse: Boolean = false,
    private val dynamicMcpGroupsProvider: () -> List<McpDeferredGroup> = { emptyList() },
    private val candidateTokens: ((JSONArray) -> Int)? = null,
    private val resolveSummaryModel: suspend () -> Pair<AgentModelClient.ModelConfig, AgentProviderClient>? = {
        val settings = AgentFeaturePreferences.load()
        ModelFeatureSelection(settings.optBoolean("compaction_custom"),
            settings.optString("compaction_custom_provider_id"), settings.optString("compaction_custom_model_id"))
            .resolve()?.let { it to ProviderClientFactory.getClient(it) }
    },
) {
    data class CompactionOutcome(
        val messages: JSONArray,
        val archiveId: String,
        val coveredUserTurns: Int,
        val usage: AgentTokenUsage?,
        val llmMs: Long,
    )

    fun compact(messages: JSONArray, systemCount: Int, sensitiveIds: Set<String>): JSONArray =
        compactWithOutcome(messages, systemCount, sensitiveIds).messages

    fun compactWithOutcome(messages: JSONArray, systemCount: Int, sensitiveIds: Set<String>): CompactionOutcome {
        var resolved = false
        var target: Pair<AgentModelClient.ModelConfig, AgentProviderClient>? = null
        return compactWith(messages, systemCount, sensitiveIds) { durable, currentTask, prefix, protectedUser, summaryBudgetTokens ->
            if (!resolved) {
                // Resolve only when there is a non-empty, safely compactable prefix.
                target = runBlocking(Dispatchers.IO) {
                    try {
                        resolveSummaryModel()
                    } catch (failure: Exception) {
                        if (failure is CancellationException || failure is AgentRunCancelledException ||
                            failure is InterruptedException || Thread.currentThread().isInterrupted) throw failure
                        null
                    }
                }
                resolved = true
            }
            controller.throwIfCancelled()
            // A different model/provider must never receive the main model's tools or cache prefix.
            AgentContextSummarizer(
                config = target?.first ?: config,
                provider = target?.second ?: provider,
                controller = controller,
                roleplay = roleplay,
                mainPrefixMessages = prefix.takeIf { target == null && enableCacheReuse },
                mainTools = mainTools.takeIf { target == null && enableCacheReuse },
                mainSessionId = mainSessionId.takeIf { target == null },
                hasProtectedUser = protectedUser,
                summaryBudgetTokens = summaryBudgetTokens,
            ).summarizeWithUsage(durable, currentTask)
        }
    }

    /** Explicit emergency path; ordinary/manual compaction must still report invalid summaries. */
    fun fallbackCompact(messages: JSONArray, systemCount: Int, sensitiveIds: Set<String>): JSONArray =
        fallbackCompactWithOutcome(messages, systemCount, sensitiveIds).messages

    fun fallbackCompactWithOutcome(messages: JSONArray, systemCount: Int, sensitiveIds: Set<String>): CompactionOutcome =
        compactWith(messages, systemCount, sensitiveIds) { durable, _, _, _, budget ->
            AgentContextSummarizer.SummaryResult(AgentContextSummarizer.fallbackSummary(durable,
                AgentContextSummarizer.maxCharsForTokenBudget(budget)), null, 0L)
        }

    private fun compactWith(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
        summarize: (List<AgentModelClient.ConversationMessage>, String, JSONArray?, Boolean, Int) -> AgentContextSummarizer.SummaryResult,
    ): CompactionOutcome {
        controller.throwIfCancelled()
        if (SignedToolContext.hasPendingResponse(messages)) throw SignedToolContext.compactionFailure(messages)
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val redactedIds = sensitiveIds + history.flatMap(AgentConversationCodec::parseToolCalls)
            .filter { AgentSensitiveToolPolicy.isSensitive(it.name) }.map { it.id }
        val latestUser = history.indexOfLast { it.optString("role") == "user" && !it.optBoolean("_eta_observation") }
        val summaryBoundary = history.indexOfLast { it.optBoolean("_eta_context_summary") } + 1
        // 必须覆盖非摘要的 durable 消息（当前 user 只作为任务上下文，始终单独保留）。
        val firstDurable = history.indices.firstOrNull {
            it != latestUser && !history[it].optBoolean("_eta_context_summary") && !history[it].optBoolean("_eta_observation")
        } ?: history.size
        val allSplits = safeSplits(history)
        val splits = allSplits.filter { it > summaryBoundary && it > firstDurable }
        if (splits.isEmpty()) throw failure("CONTEXT_NOT_COMPACTABLE", "没有可安全压缩的完整历史批次。")
        val suffixChars = LongArray(history.size + 1)
        for (index in history.indices.reversed()) suffixChars[index] = suffixChars[index + 1] + history[index].toString().length
        // 源码约 0.4–0.6 token/字符；窗口 × 1.2 字符在 300K 下保留预算 360K 字符，
        // 约 144K–216K tokens（48%–72%）。同时限制为历史的 40%，预算上限最多 480K 字符。
        // 优先保留最近四轮完整用户交换，可暂超预算；不够缩减时仍按既有逻辑缩尾重试。
        val windowCapChars = config.knownContextWindow?.let { (it.toLong() * 12L) / 10L } ?: 96_000L
        val targetChars = minOf(maxOf(96_000L, windowCapChars), 480_000L, suffixChars[0] * 4 / 10)
        val budgetSplit = splits.first { suffixChars[it] <= targetChars || it == splits.last() }
        val users = history.indices.filter { history[it].optString("role") == "user" && !history[it].optBoolean("_eta_observation") }
        val closedUsers = users.filterIndexed { index, start ->
            val next = users.getOrNull(index + 1) ?: history.size
            val found = allSplits.binarySearch(next)
            val lastSplit = if (found >= 0) found else -found - 2
            lastSplit >= 0 && allSplits[lastSplit] > start
        }
        val keepFrom = closedUsers.takeLast(4).firstOrNull()
        val recentSplit = keepFrom?.let { start -> allSplits.lastOrNull { it <= start } }
            ?: allSplits[(allSplits.size - 3).coerceAtLeast(0)]
        // 最近四轮约束若只能保留到旧摘要边界，就改用预算边界，不能只重写旧摘要。
        var splitIndex = splits.indexOf(if (recentSplit < splits.first()) budgetSplit else minOf(budgetSplit, recentSplit))
        val currentTask = history.getOrNull(latestUser)?.let {
            AgentContextSummarizer.taskText(AgentConversationCodec.transcript(JSONArray().put(it), 0, redactedIds).single())
        }.orEmpty()
        val originalSize = messages.toString().length.toLong()
        val beforeTokens = costOf(messages).toLong()
        // 至少减少 15%；已知窗口另预留 20% 给下一轮输出/工具增量。
        // 固定前缀或最新请求本身放不下时不放宽目标，而是有界返回 CONTEXT_NO_REDUCTION。
        val targetTokens = minOf(beforeTokens * 85 / 100,
            config.knownContextWindow?.let { it.toLong() * 80 / 100 } ?: Long.MAX_VALUE)
        var summaryResult: AgentContextSummarizer.SummaryResult? = null
        while (true) {
            controller.throwIfCancelled()
            val end = splits[splitIndex]
            val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
            val source = JSONArray(history.take(end).filterNot { it === protectedUser || it.optBoolean("_eta_observation") })
            val durable = AgentConversationCodec.transcript(source, 0, redactedIds)
            if (durable.isNotEmpty()) {
                val previousSummaries = history.take(end).filter { it.optBoolean("_eta_context_summary") }
                val archiveSeq = maxOf(previousSummaries.size, previousSummaries.maxOfOrNull {
                    ARCHIVE_ID_REGEX.matchEntire(it.optString("_eta_compaction_archive_id"))
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                } ?: 0) + 1
                val archiveId = "fold-$archiveSeq"
                val previousCovered = previousSummaries.sumOf { it.optInt("_eta_compacted_users") }
                val covered = durable.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
                val archiveHeader = buildString {
                    append("[Eta 上下文摘要 · 归档 ").append(archiveId)
                    if (covered > 0) {
                        append(" · 覆盖第 1–").append(covered).append(" 轮用户对话")
                        if (previousCovered > 0 && covered > previousCovered) {
                            append("（本次新增第 ").append(previousCovered + 1).append("–").append(covered).append(" 轮）")
                        }
                    }
                    append("：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。")
                    append("如需核对原文细节、完整命令输出、路径、标识或数值，可调用 ")
                    append(AgentConversationToolCatalog.READ_HISTORY)
                    append(" 按 query 检索或按 message_index 分页展开。]\n")
                }
                val groups = AgentMcpContext.compactedGroupIds(durable, dynamicMcpGroupsProvider())
                fun buildResult(summary: String): JSONArray {
                    val result = JSONArray()
                    for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
                    result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
                        role = "assistant",
                        content = AgentMcpContext.appendMarker("$archiveHeader$summary", groups),
                        contextSummary = true,
                        compactedUserTurns = covered,
                        compactionArchiveId = archiveId,
                        summaryThroughUserTurn = maxOf(covered + if (protectedUser != null) 1 else 0,
                            durable.maxOfOrNull { it.summaryThroughUserTurn } ?: 0),
                    )))
                    protectedUser?.let { result.put(retainedMessage(it, redactedIds)) }
                    history.drop(end).forEach { result.put(retainedMessage(it, redactedIds)) }
                    return result
                }
                // 空正文仍计入真实归档头、MCP 标记、系统前缀、最新 user 与完整尾部的成本。
                val summaryBudget = targetTokens - costOf(buildResult(""))
                if (summaryBudget >= MIN_SUMMARY_BUDGET_TOKENS) {
                    // Invalid/failed LLM responses propagate immediately; only a valid summary
                    // that does not reduce size is allowed to retry with a smaller tail.
                    val prefix = if (enableCacheReuse) {
                        val rawPrefix = JSONArray()
                        for (index in 0 until systemCount) {
                            rawPrefix.put(JSONObject(messages.getJSONObject(index).toString()).apply {
                                remove("_eta_assistant_screen_context")
                            })
                        }
                        // Codec projection strips images, sensitive data and opaque provider state.
                        // Ordinary text/tool messages retain the main request's reusable prefix.
                        val prefixHistory = JSONArray(history.take(end).filterNot { it.optBoolean("_eta_observation") })
                        AgentConversationCodec.transcript(prefixHistory, 0, redactedIds).forEach {
                            rawPrefix.put(AgentConversationCodec.toJsonObject(it.copy(reasoningContent = "")))
                        }
                        messageProjector?.invoke(rawPrefix) ?: rawPrefix
                    } else null
                    val attempt = summarize(durable, currentTask, prefix, protectedUser != null,
                        AgentTokenEstimator.bounded(summaryBudget))
                    summaryResult = attempt.after(summaryResult)
                    val result = buildResult(AgentContextSummarizer.stripSummaryHeader(attempt.summary))
                    // 字符仅为序列化安全兜底，缩减合同以请求投影后的 token 为准。
                    // 允许短历史增加有界的归档头，但不允许候选无界膨胀（例如投影遗漏附件）。
                    val serializedLimit = originalSize + maxOf(4_096L, originalSize / 10)
                    if (!attempt.exceededBudget && costOf(result) <= targetTokens &&
                        result.toString().length.toLong() <= serializedLimit) return CompactionOutcome(
                        messages = result, archiveId = archiveId, coveredUserTurns = covered,
                        usage = summaryResult.usage, llmMs = summaryResult.llmMs,
                    )
                }
            }
            if (splitIndex == splits.lastIndex) break
            val smallerTail = suffixChars[end] / 2
            splitIndex = (splitIndex + 1..splits.lastIndex).first {
                suffixChars[splits[it]] <= smallerTail || it == splits.lastIndex
            }
        }
        throw failure("CONTEXT_NO_REDUCTION", "保留前缀、最新消息或摘要超出压缩预算，原始上下文已保留。")
    }

    private fun costOf(messages: JSONArray): Int = candidateTokens?.invoke(messages) ?: AgentTokenEstimator.bounded(
        AgentTokenEstimator.messages((messageProjector ?: AssistantScreenContextProjection::project)(messages)).toLong() +
            (mainTools?.let { AgentTokenEstimator.json(it) } ?: 0),
    )

    private fun retainedMessage(message: JSONObject, sensitiveIds: Set<String>): JSONObject {
        // Live observations still belong to the next provider round. They never enter
        // the summary or a durable snapshot; preserve their identity for loop cleanup.
        if (message.optBoolean("_eta_observation")) return message
        val clean = AgentConversationCodec.transcript(JSONArray().put(message), 0, sensitiveIds).single()
        val result = AgentConversationCodec.toJsonObject(clean.copy(reasoningContent = ""))
        if (clean.role == "user") result.put("content", message.opt("content"))
        // Keep automatic screen context for its current round, outside durable history.
        AssistantScreenContextProjection.copy(message, result)
        return result
    }

    companion object {
        private const val MIN_SUMMARY_BUDGET_TOKENS = 128L
        private val ARCHIVE_ID_REGEX = Regex("^fold-(\\d+)$")

        /** Scan once: long tool-heavy histories must not require quadratic split checks. */
        private fun safeSplits(history: List<JSONObject>): List<Int> = buildList {
            val open = linkedSetOf<String>()
            history.forEachIndexed { index, message ->
                AgentConversationCodec.parseToolCalls(message).forEach { open += it.id }
                if (message.optString("role") == "tool") open.remove(message.optString("tool_call_id"))
                if (open.isEmpty() && message.optString("role") != "user" &&
                    history.getOrNull(index + 1)?.optString("role") != "tool") add(index + 1)
            }
        }

        fun canSplit(history: List<JSONObject>, end: Int): Boolean =
            end > 0 && end <= history.size && end in safeSplits(history)

        fun failure(code: String, message: String) = AgentModelFailure(code, false, message)
    }
}
