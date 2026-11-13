package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.mcp.McpDeferredGroup
import io.github.mangi.eta.agent.mcp.McpGroupCatalog
import io.github.mangi.eta.agent.mcp.AgentToolSearchCatalog
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray

/**
 * 工具目录的唯一构建入口。
 *
 * 请求侧（[AgentModelClient]）与「当前生效提示词」预览共用这一份，
 * 避免两处各写一份 flag 导致"预览 19 个、实际发 34 个"这类对不上。
 */
internal object AgentToolDirectory {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean,
        deviceSensitiveReadTools: Boolean,
        deviceSensitiveActionTools: Boolean,
        skillGitHubDiscovery: Boolean,
        skillGitHubInstall: Boolean,
        memoryTools: Boolean,
        memoryWritable: Boolean,
        capabilities: AgentToolCapabilities,
        localWebSearch: Boolean,
        policy: AgentToolPolicy,
        disabledTools: Set<String> = Prefs.disabledTools(),
        additionalTools: JSONArray? = null,
        restrictedToolNames: Set<String>? = null,
        rewriteReply: Boolean = false,
        openableMcpGroups: List<McpDeferredGroup> = emptyList(),
        mcpSearchEnabled: Boolean = true,
        toolFilter: ((JSONArray) -> JSONArray)? = null,
    ): JSONArray {
        if (rewriteReply) return JSONArray()
        val tools = AgentToolCatalog.build(
            terminalTools = terminalTools,
            browserTools = browserTools,
            deviceDirectTools = deviceDirectTools,
            deviceSensitiveReadTools = deviceSensitiveReadTools,
            deviceSensitiveActionTools = deviceSensitiveActionTools,
            skillGitHubDiscovery = skillGitHubDiscovery,
            skillGitHubInstall = skillGitHubInstall,
            memoryTools = memoryTools,
            memoryWritable = memoryWritable,
            capabilities = capabilities,
            localWebSearch = localWebSearch,
            disabledTools = disabledTools,
        )
        if (additionalTools != null) {
            for (index in 0 until additionalTools.length()) {
                tools.put(additionalTools.opt(index))
            }
        }
        val policyTools = policy.filterTools(tools).let { toolFilter?.invoke(it) ?: it }
        val filtered = if (restrictedToolNames == null) {
            policyTools
        } else JSONArray().also { filtered ->
            for (index in 0 until policyTools.length()) {
                val schema = policyTools.getJSONObject(index)
                if (schema.optJSONObject("function")?.optString("name") in restrictedToolNames) filtered.put(schema)
            }
        }
        // These navigation tools inherit permission from the permitted MCP groups.
        // Apply policy/profile filtering to MCP tools before deriving openableMcpGroups.
        McpGroupCatalog.appendTo(filtered, openableMcpGroups)
        AgentToolSearchCatalog.appendTo(filtered, openableMcpGroups, mcpSearchEnabled)
        return filtered
    }
}
