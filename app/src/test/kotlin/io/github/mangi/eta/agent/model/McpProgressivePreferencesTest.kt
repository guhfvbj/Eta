package io.github.mangi.eta.agent.model

import android.app.Application
import io.github.mangi.eta.agent.mcp.McpProgressiveLoader
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpRunTool
import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class McpProgressivePreferencesTest {
    @Before
    fun setUp() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        Prefs.localAgentPreferences()!!.edit().clear().commit()
    }

    @After
    fun tearDown() {
        Prefs.localAgentPreferences()!!.edit().clear().commit()
    }

    @Test
    fun loadingModeUsesLocalStringPreferencesAndDefaultsToSearch() {
        val preferences = Prefs.localAgentPreferences()!!
        assertEquals("search", Prefs.mcpLoadingMode())
        assertEquals("agent_mcp_loading_mode", Prefs.Keys.AGENT_MCP_LOADING_MODE)
        for (mode in listOf("off", "group", "search")) {
            Prefs.setMcpLoadingMode(mode)
            assertEquals(mode, Prefs.mcpLoadingMode())
            assertEquals(mode, preferences.getString(Prefs.Keys.AGENT_MCP_LOADING_MODE, null))
        }
        preferences.edit().putString(Prefs.Keys.AGENT_MCP_LOADING_MODE, "unknown").commit()
        assertEquals("search", Prefs.mcpLoadingMode())
        Prefs.setMcpLoadingMode("unknown")
        assertEquals("search", preferences.getString(Prefs.Keys.AGENT_MCP_LOADING_MODE, null))
        preferences.edit().putBoolean(Prefs.Keys.AGENT_MCP_LOADING_MODE, true).commit()
        assertEquals("search", Prefs.mcpLoadingMode())
        assertFalse(Prefs.Keys.AGENT_MCP_LOADING_MODE in Prefs.Keys.BOOLEAN_DEFAULTS)
        assertFalse(Prefs.Keys.AGENT_MCP_LOADING_MODE in Prefs.Keys.LOCAL_AGENT_KEYS)
    }

    @Test
    fun allowlistPermitsLoadersForAllowedMcpToolsButNeverLeaksDisallowedToolsOrServers() {
        for (search in listOf(false, true)) {
            val allowed = tool("allowed", "read")
            val sibling = tool("allowed", "delete")
            val denied = tool("denied", "read")
            val snapshot = McpRunSnapshot(listOf(allowed, sibling, denied))
            assertTrue(AgentToolPolicyStore.save(AgentToolPolicy(ToolPolicyMode.ALLOW_LIST, setOf(allowed.modelName))))
            val opened = ConcurrentHashMap.newKeySet<String>()
            val loader = McpProgressiveLoader(snapshot, opened, search)
            var round = 0
            var executions = 0
            AgentModelClient.complete(config, "use MCP", AgentModelClient.ToolExecutor { call ->
                if (call.name in McpProgressiveLoader.names) loader.execute(call) else {
                    assertEquals(allowed.modelName, call.name)
                    executions++
                    AgentModelClient.ToolResult("ok")
                }
            }, mcpProjection = snapshot, mcpSearchEnabled = search, openedMcpGroupsProvider = { opened.toSet() },
                provider = provider { request ->
                    val names = request.tools.names()
                    when (++round) {
                        1 -> {
                            assertEquals(if (search) McpProgressiveLoader.names else setOf("tools_use_group"), names)
                            val index = (0 until request.messages.length()).map(request.messages::getJSONObject)
                                .last { it.optString("role") == "system" }.getString("content")
                            assertTrue(index.contains("mcp:allowed"))
                            assertFalse(index.contains("mcp:denied"))
                            assertFalse(index.contains("delete"))
                            if (search) response("tools_search", JSONObject().put("query", "select:${allowed.modelName},${sibling.modelName},${denied.modelName}").toString())
                            else response("tools_use_group", """{"group":"mcp:allowed"}""")
                        }
                        2 -> {
                            assertEquals(setOf("mcp:allowed"), opened)
                            assertEquals(setOf(allowed.modelName), names)
                            assertFalse(request.messages.toString().contains("TOOL_NOT_ALLOWED_BY_POLICY"))
                            response(allowed.modelName, "{}")
                        }
                        else -> response()
                    }
                })
            assertEquals(1, executions)
        }
    }

    @Test
    fun emptyAllowlistDoesNotPublishLoadersOrMcpIndex() {
        assertTrue(AgentToolPolicyStore.save(AgentToolPolicy(ToolPolicyMode.ALLOW_LIST)))
        val snapshot = McpRunSnapshot(listOf(tool("denied", "read")))
        AgentModelClient.complete(config, "hello", AgentModelClient.ToolExecutor { error("No tools allowed") },
            mcpProjection = snapshot, provider = provider { request ->
                assertEquals(0, request.tools.length())
                assertFalse(request.messages.toString().contains("mcp:denied"))
                response()
            })
    }

    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "fixture", model = "fixture", systemPrompt = "fixed",
        browserTools = false,
    )
    private fun tool(server: String, name: String) = McpRunTool(
        "mcp_${server}_${name}_12345678", McpServerSetting(server, server, "https://example.invalid/mcp"),
        McpToolDefinition(name, inputSchemaJson = """{"type":"object"}"""), null,
    )
    private fun JSONArray.names() = (0 until length()).map {
        getJSONObject(it).getJSONObject("function").getString("name")
    }.toSet()
    private fun response(tool: String? = null, arguments: String = "{}") = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", if (tool == null) "done" else "")
        .put("finish_reason", if (tool == null) "stop" else "tool_calls")
        .also { if (tool != null) it.put("tool_calls", JSONArray().put(JSONObject()
            .put("id", "call").put("type", "function")
            .put("function", JSONObject().put("name", tool).put("arguments", arguments)))) })
    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "policy-fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request)
    }
}
