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
        io.github.mangi.eta.config.Prefs.initLocal(context)
        io.github.mangi.eta.agent.prompt.AgentToolPolicyStore.save(io.github.mangi.eta.agent.prompt.AgentToolPolicy())
        context.getSharedPreferences("eta_agent_features", 0).edit().clear().commit()
        context.getSharedPreferences("eta_tool_sessions", 0).edit().clear().commit()
    }
    private fun session() = AgentFeatureSession(context, "test-conversation", "test", emptyList(),
        AgentModelClient.ModelConfig(baseUrl="https://example.test", apiKey="test", model="test", systemPrompt="test"),
        AgentRunController(), { false }, mapOf("mcp_one" to "mcp:server", "mcp_two" to "mcp:server"), mcpLabels=mapOf("mcp:server" to "GitHub"))
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
        io.github.mangi.eta.agent.prompt.AgentToolPolicyStore.save(io.github.mangi.eta.agent.prompt.AgentToolPolicy(io.github.mangi.eta.agent.prompt.ToolPolicyMode.ALLOW_LIST,setOf("get_current_context","mcp_two")))
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
        // An unconfigured model is explicitly fail-open and reported, not a fake follow-current selection.
        assertTrue("tools_search" in names)
    }
    @Test fun disabledGroupCannotBeReopenedEvenInFailOpenMode() {
        io.github.mangi.eta.agent.prompt.AgentToolPolicyStore.save(io.github.mangi.eta.agent.prompt.AgentToolPolicy(io.github.mangi.eta.agent.prompt.ToolPolicyMode.ALLOW_LIST,setOf("get_current_context")))
        val session = session()
        session.project(catalog())
        val response = session.execute(call("tools_use_group", "{\"tools\":[\"mcp_one\"]}"), AgentModelClient.ToolExecutor { error("never") })
        assertFalse(JSONObject(response.content).getBoolean("ok"))
    }
    @Test fun childExecutionCannotBypassDisabledGroup() {
        val session = session()
        session.project(catalog())
        io.github.mangi.eta.agent.prompt.AgentToolPolicyStore.save(io.github.mangi.eta.agent.prompt.AgentToolPolicy(io.github.mangi.eta.agent.prompt.ToolPolicyMode.ALLOW_LIST,setOf("get_current_context")))
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
    @Test fun mcpDiscoveryIncludesCapabilitiesForRoutingInsteadOfOpaqueServerIdsOnly() {
        val projected = session().project(catalog())
        val loader = (0 until projected.length()).map { projected.getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == "tools_use_group" }
        assertTrue(loader.getString("description").contains("GitHub"))
        assertFalse(loader.getString("description").contains("mcp_one"))
        assertTrue(loader.getString("description").length < 600)
    }
    @Test fun webGroupSwitchAlsoRestrictsProviderHostedSearch() {
        val session = session()
        assertTrue(session.allowsHostedSearch())
        io.github.mangi.eta.agent.prompt.AgentToolPolicyStore.save(io.github.mangi.eta.agent.prompt.AgentToolPolicy(io.github.mangi.eta.agent.prompt.ToolPolicyMode.ALLOW_LIST,setOf("get_current_context")))
        assertFalse(session.allowsHostedSearch())
    }
    @Test fun manualTitleProtectionSurvivesPreferenceReload() {
        AgentFeaturePreferences.protectTitle("conversation", context)
        assertTrue(AgentFeaturePreferences.manualTitle("conversation", context))
        assertFalse(ConversationTitleModel.mayApplyRefresh(true, true, "manual", "manual", true))
        assertFalse(ConversationTitleModel.mayApplyRefresh(true, false, "old", "old", false))
    }
    @Test fun groupModeOffersOnlyWholeGroupLoadingAndOffModeOffersNoLoaders() {
        AgentFeaturePreferences.set("loading","group",context)
        val grouped=session()
        val names=names(grouped.project(catalog()))
        assertTrue("tools_use_group" in names);assertFalse("tools_search" in names)
        val rejected=grouped.execute(call("tools_use_group","{\"tools\":[\"mcp_one\"]}"),AgentModelClient.ToolExecutor { error("never") })
        assertEquals("GROUP_MODE",JSONObject(rejected.content).getString("code"))
        context.getSharedPreferences("eta_tool_sessions",0).edit().clear().commit()
        AgentFeaturePreferences.set("loading","off",context)
        val all=names(session().project(catalog()))
        assertTrue(all.containsAll(listOf("mcp_one","mcp_two")))
        assertFalse("tools_use_group" in all);assertFalse("tools_search" in all)
    }
    @Test fun openedMcpGroupDisappearsFromLoaderIndexAndChildSchemasRespectLiveWhitelist() {
        val session=session();session.project(catalog())
        session.execute(call("tools_use_group","{\"group\":\"mcp:server\"}"),AgentModelClient.ToolExecutor { error("never") })
        assertFalse("tools_use_group" in names(session.project(catalog())))
        io.github.mangi.eta.agent.prompt.AgentToolPolicyStore.save(io.github.mangi.eta.agent.prompt.AgentToolPolicy(io.github.mangi.eta.agent.prompt.ToolPolicyMode.ALLOW_LIST,setOf("get_current_context")))
        assertEquals(setOf("get_current_context"),names(session.filterChildTools(catalog())))
    }
    @Test fun ephemeralBackgroundRunNeverWritesPermanentLoadingKeys() {
        val ephemeral=AgentFeatureSession(context,"ephemeral-run","test",emptyList(),AgentModelClient.ModelConfig(baseUrl="https://example.test",apiKey="test",model="test",systemPrompt=""),AgentRunController(),{false},mapOf("mcp_one" to "mcp:server"),persistentSession=false)
        ephemeral.project(catalog())
        ephemeral.execute(call("tools_use_group","{\"group\":\"mcp:server\"}"),AgentModelClient.ToolExecutor { error("never") })
        assertTrue(context.getSharedPreferences("eta_tool_sessions",0).all.isEmpty())
    }
    @Test fun actualAuthorInvocationCreatesRecipeVisibleAndExecutableNextRound() {
        val store=io.github.mangi.eta.agent.tool.AgentToolRecipeStore.get(context);store.replaceAll(emptyList())
        val session=session()
        fun full()=JSONArray(catalog().toString()).also { array -> val extra=session.extraSchemas();for(i in 0 until extra.length()) array.put(extra.get(i)) }
        val delegate=AgentModelClient.ToolExecutor { error("literal recipe is executed internally") }
        session.project(full())
        session.execute(call("tools_use_group","{\"group\":\"extensions\"}"),delegate)
        session.project(full())
        val args="""{"action":"create","name":"echo_live","recipe":{"name":"echo_live","description":"回显","parameters":{"type":"object","properties":{}},"steps":[{"op":"literal","text":"hello"}]}}"""
        try {
            val created = session.execute(call("eta_author_tool",args),delegate)
            assertFalse(created.sensitive)
            val result=JSONObject(created.content)
            assertTrue(result.toString(),result.getBoolean("ok"))
            assertFalse(session.execute(call("eta_list_recipes"),delegate).sensitive)
            assertTrue("recipe_echo_live" in names(session.project(full())))
            assertEquals("hello",JSONObject(session.execute(call("recipe_echo_live"),delegate).content).getString("result"))
        } finally { store.replaceAll(emptyList()) }
    }
    @Test fun cancellingChildExecutionNeverCancelsParentOrExecutesTheTool() {
        val parent=AgentRunController();val child=AgentRunController()
        val session=AgentFeatureSession(context,"parent-conversation","write request",emptyList(),AgentModelClient.ModelConfig(baseUrl="https://example.test",apiKey="test",model="test",systemPrompt=""),parent,{false})
        var invoked=false
        child.cancel()
        assertThrows(io.github.mangi.eta.agent.runtime.AgentRunCancelledException::class.java) {
            session.execute(call("write_file"),AgentModelClient.ToolExecutor { invoked=true;AgentModelClient.ToolResult("bad") },requireLoaded=false,executionController=child)
        }
        assertFalse(parent.isCancelled);assertFalse(invoked)
    }
    @Test fun oldReviewModesCannotBlockNativeOrChildTools() {
        val legacy=JSONObject().put("migration",2).put("loading","off").put("review_mode","readonly")
            .put("review_engine","decision").put("review_model","old-model").put("review_timeout",20000)
        context.getSharedPreferences("eta_agent_features",0).edit().putString("settings",legacy.toString()).commit()
        val manager=context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("eta_tool_authorization","legacy",3))
        val session=session()
        assertNull(manager.getNotificationChannel("eta_tool_authorization"))
        val cfg=JSONArray().put(AgentFeatureSession.schema("terminal","command",JSONObject()))
        assertTrue("terminal" in names(session.project(cfg)))
        val command="{\"command\":\"rm -rf /data\"}"
        val invoked=mutableListOf<String>()
        val delegate=AgentModelClient.ToolExecutor { invoked+=it.argumentsJson;AgentModelClient.ToolResult("simulated; no shell executed") }
        assertEquals("simulated; no shell executed",session.execute(call("terminal",command),delegate).content)
        assertEquals("simulated; no shell executed",session.execute(call("terminal",command),delegate,requireLoaded=false).content)
        assertEquals(listOf(command,command),invoked)
        val migrated=AgentFeaturePreferences.load(context)
        assertEquals(3,migrated.getInt("migration"))
        assertFalse(migrated.keys().asSequence().any { it.startsWith("review_") })
    }
    @Test fun childLikeFilteredCatalogRemovesRevokedSchemasOnTheNextRound() {
        var allowed=true;var round=0
        val cfg=AgentModelClient.ModelConfig(baseUrl="https://example.test",apiKey="test",model="test",systemPrompt="",autoCompactionEnabled=false)
        val provider=object:AgentProviderClient {
            override val id="filtered-child"
            override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,false,false,false,false,false,false)
            override fun complete(request:ProviderRequest,runController:AgentRunController,onEvent:(ProviderEvent)->Unit):ProviderResponse {
                val message=JSONObject().put("role","assistant")
                if(round++==0) {
                    assertTrue("mcp_one" in names(request.tools))
                    message.put("content","").put("finish_reason","tool_calls").put("tool_calls",JSONArray().put(JSONObject().put("id","remote").put("type","function").put("function",JSONObject().put("name","mcp_one").put("arguments","{}"))))
                } else {
                    assertFalse("mcp_one" in names(request.tools))
                    message.put("content","done").put("finish_reason","stop")
                }
                return ProviderResponse(message)
            }
        }
        AgentModelClient.complete(cfg,"test",AgentModelClient.ToolExecutor { allowed=false;AgentModelClient.ToolResult("ok") },provider=provider,
            additionalTools=JSONArray().put(catalog().getJSONObject(1)),toolFilter={ tools -> JSONArray().also { result ->
                for(i in 0 until tools.length()) if(allowed || tools.getJSONObject(i).getJSONObject("function").getString("name")!="mcp_one") result.put(tools.get(i))
            } })
        assertEquals(2,round)
    }
    @Test fun localAdditionalToolDescriptionIsNotCompactedAsMcp() {
        val longDescription="Authorized models: "+"gemini_model ".repeat(100)
        val provider=object:AgentProviderClient {
            override val id="local-description"
            override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,false,false,false,false,false,false)
            override fun complete(request:ProviderRequest,runController:AgentRunController,onEvent:(ProviderEvent)->Unit):ProviderResponse {
                val native=(0 until request.tools.length()).map { request.tools.getJSONObject(it).getJSONObject("function") }.single { it.getString("name")=="subagent" }
                assertEquals(longDescription,native.getString("description"))
                return ProviderResponse(JSONObject().put("role","assistant").put("content","done").put("finish_reason","stop"))
            }
        }
        AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl="https://example.test",apiKey="test",model="test",systemPrompt="",autoCompactionEnabled=false),"test",AgentModelClient.ToolExecutor { error("no call") },provider=provider,
            additionalTools=JSONArray().put(AgentFeatureSession.schema("subagent",longDescription,JSONObject())))
    }
}
