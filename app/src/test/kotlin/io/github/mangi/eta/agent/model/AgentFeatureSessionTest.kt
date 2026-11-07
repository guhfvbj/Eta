package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner

@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [36])
class AgentFeatureSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        context.getSharedPreferences("eta_agent_features", 0).edit().clear().commit()
        context.getSharedPreferences("eta_tool_sessions", 0).edit().clear().commit()
        AgentFeaturePreferences.set("review_mode", "auto", context)
    }
    private fun session() = AgentFeatureSession(context, "test-conversation", "test", emptyList(),
        AgentModelClient.ModelConfig(baseUrl="https://example.test", apiKey="test", model="test", systemPrompt="test"),
        AgentRunController(), { false }, mapOf("mcp_one" to "mcp:server", "mcp_two" to "mcp:server"))
    private fun catalog() = JSONArray().put(AgentFeatureSession.schema("get_current_context", "context", JSONObject()))
        .put(AgentFeatureSession.schema("mcp_one", "one", JSONObject())).put(AgentFeatureSession.schema("mcp_two", "two", JSONObject()))
    private fun names(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
    private fun call(name: String, args: String = "{}") = AgentModelClient.ToolCall("id", name, args)
    @Test fun preciseMcpLoadingTakesEffectNextRoundWithoutOpeningItsNeighbor() {
        val session = session()
        assertFalse("mcp_one" in names(session.project(catalog())))
        var invoked = false
        val delegate = AgentModelClient.ToolExecutor { invoked = true; AgentModelClient.ToolResult("ok") }
        session.execute(call("tools_search", "{\"query\":\"select:mcp_one\"}"), delegate)
        session.execute(call("mcp_one"), delegate)
        assertFalse(invoked)
        val next = names(session.project(catalog()))
        assertTrue("mcp_one" in next); assertFalse("mcp_two" in next)
        session.execute(call("mcp_one"), delegate)
        assertTrue(invoked)
    }
    @Test fun disablingAnAlreadyLoadedToolImmediatelyBlocksExecutionAndSearch() {
        val session = session()
        session.project(catalog())
        session.execute(call("tools_use_group", "{\"group\":\"mcp:server\"}"), AgentModelClient.ToolExecutor { error("not executed") })
        session.project(catalog())
        AgentFeaturePreferences.set("disabled_tools", "mcp_one", context)
        var invoked = false
        val result = session.execute(call("mcp_one"), AgentModelClient.ToolExecutor { invoked=true; AgentModelClient.ToolResult("bad") })
        assertFalse(invoked); assertFalse(JSONObject(result.content).getBoolean("ok"))
        assertFalse("mcp_one" in names(session.project(catalog())))
    }
    @Test fun failedControlDecisionFallsBackClosedWithoutBypassingGroupPermissions() {
        AgentFeaturePreferences.set("decision_enabled", true, context)
        AgentFeaturePreferences.set("decision_shadow", false, context)
        AgentFeaturePreferences.set("decision_mode", "control", context)
        AgentFeaturePreferences.set("decision_failure", "closed", context)
        val names = names(session().project(catalog()))
        assertTrue("get_current_context" in names)
        assertFalse("mcp_one" in names)
        assertTrue("tools_search" in names)
    }
    @Test fun disabledGroupCannotBeReopenedEvenInFailOpenMode() {
        AgentFeaturePreferences.set("group_mcp:server", false, context)
        val session = session()
        session.project(catalog())
        val response = session.execute(call("tools_use_group", "{\"tools\":[\"mcp_one\"]}"), AgentModelClient.ToolExecutor { error("never") })
        assertFalse(JSONObject(response.content).getBoolean("ok"))
    }
    @Test fun childExecutionCannotBypassDisabledGroup() {
        val session = session()
        session.project(catalog())
        AgentFeaturePreferences.set("group_mcp:server", false, context)
        val result = session.execute(call("mcp_one"), AgentModelClient.ToolExecutor { error("must not execute") }, requireLoaded = false)
        assertFalse(JSONObject(result.content).getBoolean("ok"))
    }
    @Test fun modelLoopPublishesAnExactMcpToolOnlyOnTheFollowingRound() {
        val session = session()
        val invoked = mutableListOf<String>()
        var round = 0
        val provider = object : AgentProviderClient {
            override val id = "feature-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, false, false, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val names = names(request.tools)
                val message = JSONObject().put("role", "assistant").put("content", "").put("finish_reason", "tool_calls")
                when (round++) {
                    0 -> {
                        assertFalse("mcp_one" in names)
                        message.put("tool_calls", JSONArray().put(JSONObject().put("id", "load").put("type", "function")
                            .put("function", JSONObject().put("name", "tools_search").put("arguments", "{\"query\":\"select:mcp_one\"}"))))
                    }
                    1 -> {
                        assertTrue("mcp_one" in names); assertFalse("mcp_two" in names)
                        message.put("tool_calls", JSONArray().put(JSONObject().put("id", "exec").put("type", "function")
                            .put("function", JSONObject().put("name", "mcp_one").put("arguments", "{}"))))
                    }
                    else -> message.put("content", "done").put("finish_reason", "stop")
                }
                return ProviderResponse(message)
            }
        }
        val response = AgentModelClient.complete(
            AgentModelClient.ModelConfig(baseUrl="https://example.test", apiKey="test", model="test", systemPrompt="", autoCompactionEnabled=false), "test",
            AgentModelClient.ToolExecutor { invoked += it.name; AgentModelClient.ToolResult("{\"ok\":true}") },
            provider=provider, featureSession=session,
            additionalTools=JSONArray().put(catalog().getJSONObject(1)).put(catalog().getJSONObject(2)),
        )
        assertEquals("done", response.content)
        assertEquals(listOf("mcp_one"), invoked)
    }
    @Test fun manualTitleProtectionSurvivesPreferenceReload() {
        AgentFeaturePreferences.protectTitle("conversation", context)
        assertTrue(AgentFeaturePreferences.manualTitle("conversation", context))
        assertFalse(ConversationTitleModel.mayApplyRefresh(true, true, "manual", "manual", true))
        assertFalse(ConversationTitleModel.mayApplyRefresh(true, false, "old", "old", false))
    }
}
