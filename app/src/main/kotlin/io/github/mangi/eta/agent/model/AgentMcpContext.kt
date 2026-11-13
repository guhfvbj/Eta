package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.mcp.McpDeferredGroup
import io.github.mangi.eta.agent.mcp.isMcpGroupId
import org.json.JSONArray

/** Durable compaction boundary metadata. Only assistant context summaries carry markers. */
internal object AgentMcpContext {
    private val marker = Regex("""<!-- eta:opened_mcp_groups:(\[[^\r\n]*?]) -->""")

    fun openedGroupIds(history: List<AgentModelClient.ConversationMessage>): Set<String> = buildSet {
        history.filter { it.contextSummary && it.role == "assistant" }.forEach { message ->
            marker.findAll(message.content).forEach markerLoop@{ match ->
                val ids = runCatching { JSONArray(match.groupValues[1]) }.getOrNull() ?: return@markerLoop
                for (index in 0 until ids.length()) {
                    (ids.opt(index) as? String)?.takeIf(::isMcpGroupId)?.let(::add)
                }
            }
        }
    }

    fun compactedGroupIds(
        history: List<AgentModelClient.ConversationMessage>,
        groups: List<McpDeferredGroup>,
    ): Set<String> {
        if (groups.isEmpty()) return emptySet()
        val names = AgentModelClient.historyToolNames(history)
        val known = groups.mapTo(linkedSetOf()) { it.id }
        return (openedGroupIds(history) + groups.filter { group -> group.toolNames.any { it in names } }
            .map { it.id }).intersect(known)
    }

    fun appendMarker(content: String, groupIds: Set<String>): String = if (groupIds.isEmpty()) content else
        content + "\n<!-- eta:opened_mcp_groups:" + JSONArray(groupIds.sorted()).toString() + " -->"
}
