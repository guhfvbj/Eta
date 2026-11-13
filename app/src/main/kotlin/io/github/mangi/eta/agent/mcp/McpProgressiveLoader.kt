package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolSchema
import io.github.mangi.eta.agent.model.AgentToolSearch
import org.json.JSONArray
import org.json.JSONObject

internal object McpGroupCatalog {
    const val USE_GROUP = "tools_use_group"

    fun appendTo(tools: JSONArray, openableGroups: List<McpDeferredGroup>) {
        if (openableGroups.isEmpty()) return
        tools.put(AgentToolSchema.function(
            USE_GROUP,
            "Load an MCP server's tools. Use a group ID from the MCP index. " +
                "Its tools become available in the next round, not in this tool batch.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().put("group", JSONObject().put("type", "string")
                    .put("enum", JSONArray(openableGroups.map { it.id }))))
                .put("required", JSONArray().put("group"))
                .put("additionalProperties", false),
        ))
    }
}

internal object AgentToolSearchCatalog {
    const val SEARCH = "tools_search"

    fun appendTo(tools: JSONArray, openableGroups: List<McpDeferredGroup>, searchEnabled: Boolean = true) {
        if (!searchEnabled || openableGroups.isEmpty()) return
        tools.put(AgentToolSchema.function(
            SEARCH,
            "Search unopened MCP tools and load their owning servers for the next round. " +
                "Use space-separated AND keywords, +term for required terms (others rank results), " +
                "an mcp_ name prefix, or select:name1,name2 for exact model tool names. " +
                "Keywords match names, descriptions and server names literally, not as regex.",
            JSONObject().put("type", "object")
                .put("properties", JSONObject()
                    .put("query", JSONObject().put("type", "string"))
                    .put("max_results", JSONObject().put("type", "integer")
                        .put("minimum", 1).put("maximum", 20).put("default", AgentToolSearch.DEFAULT_RESULTS)))
                .put("required", JSONArray().put("query"))
                .put("additionalProperties", false),
        ))
    }
}

/** Only changes the next round's projection; never invokes an MCP tool itself. */
internal class McpProgressiveLoader(
    private val projection: AgentModelClient.McpToolProjection,
    private val openedGroupIds: MutableSet<String>,
    private val searchEnabled: Boolean = true,
    private val toolAllowed: (String) -> Boolean = { true },
) : AgentModelClient.ToolExecutor {
    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (toolCall.name == AgentToolSearchCatalog.SEARCH && !searchEnabled) {
            return failure("TOOL_SEARCH_DISABLED", "MCP tool search is disabled; use tools_use_group.")
        }
        val arguments = runCatching { JSONObject(toolCall.argumentsJson) }.getOrNull()
            ?: return failure("INVALID_ARGUMENT", "Arguments must be a JSON object.")
        val groups = projection.deferredGroups(toolAllowed)
        val openable = groups.filterNot { it.id in openedGroupIds }.mapTo(linkedSetOf()) { it.id }
        return when (toolCall.name) {
            McpGroupCatalog.USE_GROUP -> {
                val group = arguments.opt("group") as? String
                    ?: return failure("INVALID_ARGUMENT", "group must be a string.")
                if (groups.none { it.id == group }) return failure("MCP_GROUP_NOT_AVAILABLE", "Unknown or disallowed MCP group.")
                openedGroupIds.add(group)
                success(JSONObject().put("group", group))
            }
            AgentToolSearchCatalog.SEARCH -> {
                val query = (arguments.opt("query") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return failure("INVALID_ARGUMENT", "query must be a non-empty string.")
                val hits = AgentToolSearch.search(
                    query, projection.searchCandidates(openable, toolAllowed),
                    arguments.optInt("max_results", AgentToolSearch.DEFAULT_RESULTS),
                )
                if (hits.isEmpty()) return failure("NO_MATCHING_TOOLS", "No matching unopened MCP tools.")
                val unlocked = hits.mapTo(linkedSetOf()) { it.groupId }
                openedGroupIds.addAll(unlocked)
                success(JSONObject()
                    .put("groups", JSONArray(unlocked.toList()))
                    .put("matches", JSONArray().also { array -> hits.forEach {
                        array.put(JSONObject().put("name", it.name).put("group", it.groupId))
                    } }))
            }
            else -> failure("UNKNOWN_TOOL", "Unknown MCP loader tool.")
        }
    }

    private fun success(payload: JSONObject) = AgentModelClient.ToolResult(payload
        .put("ok", true).put("available", "next_turn")
        .put("message", "MCP server tools are available from the next round onward.").toString())

    private fun failure(code: String, message: String) = AgentModelClient.ToolResult(JSONObject()
        .put("ok", false).put("code", code).put("message", message).toString())

    companion object {
        val names: Set<String> = setOf(McpGroupCatalog.USE_GROUP, AgentToolSearchCatalog.SEARCH)
    }
}
