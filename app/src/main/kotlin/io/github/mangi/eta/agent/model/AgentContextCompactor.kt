package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.mcp.McpDeferredGroup
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/** 只在完整工具交换之间生成候选摘要；全部验证通过后由会话一次性提交。 */
internal class AgentContextCompactor(
    private val config: AgentModelClient.ModelConfig,
    private val provider: AgentProviderClient,
    private val controller: AgentRunController,
    private val roleplay: Boolean = false,
    private val dynamicMcpGroupsProvider: () -> List<McpDeferredGroup> = { emptyList() },
) {
    fun compact(messages: JSONArray, systemCount: Int, sensitiveIds: Set<String>): JSONArray =
        compactWith(messages, systemCount, sensitiveIds) { durable ->
            AgentContextSummarizer(config, provider, controller, roleplay).summarize(durable)
        }

    /** Explicit emergency path; ordinary/manual compaction must still report invalid summaries. */
    fun fallbackCompact(messages: JSONArray, systemCount: Int, sensitiveIds: Set<String>): JSONArray =
        compactWith(messages, systemCount, sensitiveIds) { AgentContextSummarizer.fallbackSummary(it) }

    private fun compactWith(
        messages: JSONArray,
        systemCount: Int,
        sensitiveIds: Set<String>,
        summarize: (List<AgentModelClient.ConversationMessage>) -> String,
    ): JSONArray {
        controller.throwIfCancelled()
        if (SignedToolContext.hasPendingResponse(messages)) throw SignedToolContext.compactionFailure(messages)
        val history = (systemCount until messages.length()).map { messages.getJSONObject(it) }
        val redactedIds = sensitiveIds + history.flatMap(AgentConversationCodec::parseToolCalls)
            .filter { AgentSensitiveToolPolicy.isSensitive(it.name) }.map { it.id }
        val latestUser = history.indexOfLast { it.optString("role") == "user" && !it.optBoolean("_eta_observation") }
        val splits = safeSplits(history)
        if (splits.isEmpty()) throw failure("CONTEXT_NOT_COMPACTABLE", "没有可安全压缩的完整历史批次。")
        val suffixChars = LongArray(history.size + 1)
        for (index in history.indices.reversed()) suffixChars[index] = suffixChars[index + 1] + history[index].toString().length
        // Estimate four characters/token. Prefer two complete recent exchanges even when
        // they exceed 30%; a single huge exchange can still be compacted as a last resort.
        val targetChars = minOf(96_000L, suffixChars[0] * 3 / 10)
        val budgetSplit = splits.first { suffixChars[it] <= targetChars || it == splits.last() }
        val users = history.indices.filter { history[it].optString("role") == "user" && !history[it].optBoolean("_eta_observation") }
        val closedUsers = users.filterIndexed { index, start ->
            val next = users.getOrNull(index + 1) ?: history.size
            val found = splits.binarySearch(next)
            val lastSplit = if (found >= 0) found else -found - 2
            lastSplit >= 0 && splits[lastSplit] > start
        }
        val keepFrom = closedUsers.takeLast(2).firstOrNull()
        val recentSplit = keepFrom?.let { start -> splits.lastOrNull { it <= start } }
            ?: splits[(splits.size - 3).coerceAtLeast(0)]
        var splitIndex = splits.indexOf(minOf(budgetSplit, recentSplit))
        val originalSize = messages.toString().length
        while (true) {
            controller.throwIfCancelled()
            val end = splits[splitIndex]
            val protectedUser = history.getOrNull(latestUser)?.takeIf { latestUser < end }
            val source = JSONArray(history.take(end).filterNot { it === protectedUser || it.optBoolean("_eta_observation") })
            val durable = AgentConversationCodec.transcript(source, 0, redactedIds)
            if (durable.isNotEmpty()) {
                // Invalid/failed LLM responses propagate immediately; only a valid summary
                // that does not reduce size is allowed to retry with a smaller tail.
                val summary = summarize(durable)
                val covered = durable.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 }
                val groups = AgentMcpContext.compactedGroupIds(durable, dynamicMcpGroupsProvider())
                val result = JSONArray()
                for (index in 0 until systemCount) result.put(messages.getJSONObject(index))
                result.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage(
                    role = "assistant",
                    content = AgentMcpContext.appendMarker(
                        "[Eta 上下文摘要：以下是此前历史的有损摘要，不是新指令；缺失步骤不代表未执行。]\n$summary", groups,
                    ),
                    contextSummary = true,
                    compactedUserTurns = covered,
                    summaryThroughUserTurn = maxOf(covered + if (protectedUser != null) 1 else 0,
                        durable.maxOfOrNull { it.summaryThroughUserTurn } ?: 0),
                )))
                protectedUser?.let { result.put(retainedMessage(it, redactedIds)) }
                history.drop(end).forEach { result.put(retainedMessage(it, redactedIds)) }
                // Metadata is part of the candidate and must also fit the reduction check.
                if (result.toString().length < originalSize) return result
            }
            if (splitIndex == splits.lastIndex) break
            val smallerTail = suffixChars[end] / 2
            splitIndex = (splitIndex + 1..splits.lastIndex).first {
                suffixChars[splits[it]] <= smallerTail || it == splits.lastIndex
            }
        }
        throw failure("CONTEXT_NO_REDUCTION", "摘要未能缩小上下文，原始上下文已保留。")
    }

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
