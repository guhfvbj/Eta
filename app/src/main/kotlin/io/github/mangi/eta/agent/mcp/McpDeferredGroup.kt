package io.github.mangi.eta.agent.mcp

/** Run-frozen MCP server index; built-in local tools never enter this index. */
internal data class McpDeferredGroup(
    val id: String,
    val summary: String,
    val toolNames: Set<String>,
    val label: String,
)

internal fun mcpGroupId(serverId: String): String = "mcp:$serverId"

internal fun isMcpGroupId(groupId: String): Boolean =
    groupId.startsWith("mcp:") && groupId.length > "mcp:".length
