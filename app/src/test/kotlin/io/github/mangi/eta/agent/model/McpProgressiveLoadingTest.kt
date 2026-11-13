package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.mcp.AgentToolSearchCatalog
import io.github.mangi.eta.agent.mcp.McpGroupCatalog
import io.github.mangi.eta.agent.mcp.McpProgressiveLoader
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpRunTool
import io.github.mangi.eta.agent.mcp.isMcpGroupId
import io.github.mangi.eta.agent.mcp.mcpGroupId
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class McpProgressiveLoadingTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "fixture", model = "fixture", systemPrompt = "fixed",
        contextWindow = 128_000, browserTools = false,
    )
    private val first = tool("one", "Acme Calendar", "findEvents")
    private val second = tool("two", "Documents", "read_file")
    private val snapshot = McpRunSnapshot(listOf(first, second))

    @Test
    fun projectsOneGroupPerPermittedServerWithBoundedOriginalNames() {
        val many = (1..8).map { tool("one", "Acme Calendar", "tool$it") }
        val projected = McpRunSnapshot(many + second)
        val groups = projected.deferredGroups { it != second.modelName && it != many[0].modelName }
        val group = groups.single()
        assertEquals("mcp:one", group.id)
        assertEquals("MCP 服务器「Acme Calendar」", group.label)
        assertEquals(many.drop(1).map { it.modelName }.toSet(), group.toolNames)
        assertEquals("MCP 服务器「Acme Calendar」的 7 个工具：tool2、tool3、tool4、tool5、tool6、tool7 等", group.summary)
        assertTrue(projected.deferredGroups { false }.isEmpty())
        assertEquals("mcp:one", mcpGroupId("one"))
        assertTrue(isMcpGroupId("mcp:one"))
        assertFalse(isMcpGroupId("local"))
        assertFalse(isMcpGroupId("mcp:"))
        assertEquals(setOf("mcp:one"), projected.openedGroupIdsFrom(listOf(many[0].modelName, "unknown")))
    }

    @Test
    fun appendAndSearchApplyBothGroupAndToolPermission() {
        val opened = JSONArray().also { snapshot.appendTools(it, setOf("mcp:one")) }
        assertEquals(setOf(first.modelName), opened.names())
        assertEquals(0, JSONArray().also { snapshot.appendTools(it, emptySet()) }.length())
        assertEquals(0, JSONArray().also { snapshot.appendTools(it, null) { false } }.length())
        assertEquals(setOf(first.modelName, second.modelName), JSONArray().also(snapshot::appendModelTools).names())
        val candidate = snapshot.searchCandidates(setOf("mcp:one")).single()
        assertEquals(first.modelName, candidate.name)
        assertEquals("Acme Calendar findEvents", candidate.searchHint)
        assertEquals(first.definition.description, candidate.description)
        assertEquals("mcp:one", candidate.groupId)
        assertTrue(candidate.mcp)
        assertTrue(snapshot.searchCandidates(setOf("mcp:one")) { false }.isEmpty())
    }

    @Test
    fun loaderSchemasAreConditionalAndGroupEnumsShrink() {
        val groups = snapshot.deferredGroups()
        fun schemas(search: Boolean, opened: Set<String>) = JSONArray().also { tools ->
            val remaining = groups.filterNot { it.id in opened }
            McpGroupCatalog.appendTo(tools, remaining)
            AgentToolSearchCatalog.appendTo(tools, remaining, search)
        }
        assertEquals(McpProgressiveLoader.names, schemas(true, emptySet()).names())
        assertEquals(setOf("tools_use_group"), schemas(false, emptySet()).names())
        val remaining = schemas(true, setOf("mcp:one"))
        assertEquals("mcp:two", remaining.getJSONObject(0).getJSONObject("function")
            .getJSONObject("parameters").getJSONObject("properties").getJSONObject("group")
            .getJSONArray("enum").getString(0))
        assertEquals(0, schemas(true, setOf("mcp:one", "mcp:two")).length())
        val locals = AgentToolCatalog.build(terminalTools = true, browserTools = true).names()
        assertTrue("terminal" in locals)
        assertTrue("browser_use" in locals)
        assertTrue(locals.intersect(McpProgressiveLoader.names).isEmpty())
    }

    @Test
    fun groupAndSearchUnlockOwningServersWithNextTurnReceipts() {
        for (search in listOf(false, true)) {
            val opened = ConcurrentHashMap.newKeySet<String>()
            val loader = McpProgressiveLoader(snapshot, opened, search)
            val result = loader.execute(if (search) call("tools_search", """{"query":"Acme calendar"}""")
                else call("tools_use_group", """{"group":"mcp:one"}"""))
            val payload = JSONObject(result.content)
            assertTrue(payload.getBoolean("ok"))
            assertEquals("next_turn", payload.getString("available"))
            assertEquals(setOf("mcp:one"), opened)
            assertEquals(setOf(first.modelName), JSONArray().also { snapshot.appendTools(it, opened) }.names())
        }
    }

    @Test
    fun loaderFailsClosedOnDisabledSearchInvalidArgumentsAndDisallowedGroups() {
        val opened = ConcurrentHashMap.newKeySet<String>()
        val loader = McpProgressiveLoader(snapshot, opened, false) { it == first.modelName }
        assertEquals("TOOL_SEARCH_DISABLED", code(loader.execute(call("tools_search", """{"query":"find"}"""))))
        assertEquals("INVALID_ARGUMENT", code(loader.execute(call("tools_use_group", "[]"))))
        assertEquals("MCP_GROUP_NOT_AVAILABLE", code(loader.execute(call("tools_use_group", """{"group":"mcp:two"}"""))))
        val search = McpProgressiveLoader(snapshot, opened) { it == first.modelName }
        assertEquals("INVALID_ARGUMENT", code(search.execute(call("tools_search", """{"query":3}"""))))
        assertEquals("INVALID_ARGUMENT", code(search.execute(call("tools_search", """{"query":" "}"""))))
        assertEquals("NO_MATCHING_TOOLS", code(search.execute(call("tools_search", """{"query":"Documents"}"""))))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun searchLoadsMultipleOwningServersButOnlyPermittedHits() {
        val opened = ConcurrentHashMap.newKeySet<String>()
        val loader = McpProgressiveLoader(snapshot, opened)
        val query = JSONObject().put("query", "select:${first.modelName},${second.modelName}")
        assertTrue(JSONObject(loader.execute(call("tools_search", query.toString())).content).getBoolean("ok"))
        assertEquals(setOf("mcp:one", "mcp:two"), opened)
        assertEquals("NO_MATCHING_TOOLS", code(loader.execute(call("tools_search", query.toString()))))
    }

    @Test
    fun modelLoopLoadsNextRoundAndRemovesIndexWithoutLosingHistory() {
        for (search in listOf(false, true)) {
            val oneServer = McpRunSnapshot(listOf(first))
            val opened = ConcurrentHashMap.newKeySet<String>()
            val loader = McpProgressiveLoader(oneServer, opened, search)
            val executed = mutableListOf<String>()
            var round = 0
            var initialSystemCount = 0
            val provider = provider { request, _ ->
                round++
                val names = request.tools.names()
                val systems = request.messages.objects().filter { it.optString("role") == "system" }
                when (round) {
                    1 -> {
                        initialSystemCount = systems.size
                        assertFalse(first.modelName in names)
                        assertTrue("get_current_context" in names)
                        assertEquals(search, "tools_search" in names)
                        assertTrue(systems.any { it.optString("content").contains("mcp:one") })
                        assertEquals(search, systems.last().getString("content").contains("tools_search"))
                        response(calls = listOf(if (search) call("tools_search", """{"query":"calendar"}""")
                            else call("tools_use_group", """{"group":"mcp:one"}""")))
                    }
                    2 -> {
                        assertEquals(initialSystemCount - 1, systems.size)
                        assertTrue(first.modelName in names)
                        assertTrue(names.intersect(McpProgressiveLoader.names).isEmpty())
                        assertEquals("load calendar", request.messages.objects().first { it.optString("role") == "user" }.getString("content"))
                        assertTrue(request.messages.objects().any { it.optString("role") == "tool" && it.optString("content").contains("next_turn") })
                        response(calls = listOf(call(first.modelName, "{}")))
                    }
                    else -> {
                        assertTrue(request.messages.toString().contains("actual MCP result"))
                        response("done")
                    }
                }
            }
            val result = AgentModelClient.complete(config, "load calendar", AgentModelClient.ToolExecutor {
                if (it.name in McpProgressiveLoader.names) loader.execute(it) else {
                    executed += it.name
                    AgentModelClient.ToolResult("actual MCP result")
                }
            }, provider = provider, mcpProjection = oneServer, mcpSearchEnabled = search,
                openedMcpGroupsProvider = { opened.toSet() })
            assertEquals(3, round)
            assertEquals("done", result.content)
            assertEquals(listOf(first.modelName), executed)
            assertEquals(listOf("assistant", "tool", "assistant", "tool", "assistant"), result.transcript.map { it.role })
        }
    }

    @Test
    fun sameBatchCannotExecuteAJustLoadedTool() {
        val opened = ConcurrentHashMap.newKeySet<String>()
        val loader = McpProgressiveLoader(snapshot, opened)
        var round = 0
        AgentModelClient.complete(config, "load", AgentModelClient.ToolExecutor {
            if (it.name in McpProgressiveLoader.names) loader.execute(it) else error("Same-batch MCP call must be rejected")
        }, provider = provider { request, _ ->
            if (++round == 1) response(calls = listOf(
                call("tools_use_group", """{"group":"mcp:one"}"""), call(first.modelName, "{}", "early"),
            )) else {
                assertTrue(first.modelName in request.tools.names())
                assertTrue(request.messages.toString().contains("TOOL_NOT_AVAILABLE"))
                response("done")
            }
        }, mcpProjection = snapshot, openedMcpGroupsProvider = { opened.toSet() })
        assertEquals(2, round)
    }

    @Test
    fun firstRoundRecoversUnionOfProviderHistoryAndCompactionMarkersAndRetainsIt() {
        val third = tool("three", "Mail", "readMail")
        val all = McpRunSnapshot(listOf(first, second, third))
        val history = listOf(
            AgentModelClient.ConversationMessage("assistant", toolCallsJson = JSONArray().put(callJson(call(first.modelName, "{}"))).toString()),
            AgentModelClient.ConversationMessage("tool", "redacted", toolCallId = "call"),
            AgentModelClient.ConversationMessage("assistant", AgentMcpContext.appendMarker("summary", setOf("mcp:two", "mcp:removed")), contextSummary = true),
        )
        var round = 0
        AgentModelClient.complete(config, "continue", AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
            history = history, mcpProjection = all, openedMcpGroupsProvider = { if (round == 0) setOf("mcp:three") else emptySet() },
            provider = provider { request, _ ->
                assertTrue(request.tools.names().containsAll(listOf(first.modelName, second.modelName, third.modelName)))
                assertTrue(request.tools.names().intersect(McpProgressiveLoader.names).isEmpty())
                if (++round == 1) response(calls = listOf(call(first.modelName, "{}"))) else response("done")
            })
        assertEquals(2, round)
    }

    @Test
    fun compactionAndChainedCompactionPreserveMarkersAndRecoverFirstRound() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed"))
        messages.put(AgentConversationCodec.toJsonObject(AgentModelClient.ConversationMessage("user", "old request")))
        messages.put(JSONObject().put("role", "assistant").put("content", "x".repeat(8_000))
            .put("tool_calls", JSONArray().put(callJson(call(first.modelName, "{}")))))
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "call").put("content", "sensitive result"))
        messages.put(JSONObject().put("role", "assistant").put("content", "completed " + "x".repeat(8_000)))
        messages.put(JSONObject().put("role", "user").put("content", "current request"))
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ -> response("short summary") },
            AgentRunController(), { setOf("call") }, {}, {}, dynamicMcpGroupsProvider = snapshot::deferredGroups)
        session.compact(force = true)
        assertEquals(setOf("mcp:one"), AgentMcpContext.openedGroupIds(session.snapshot()!!.messages))
        assertEquals("current request", messages.getJSONObject(messages.length() - 1).getString("content"))
        messages.put(JSONObject().put("role", "assistant").put("content", "y".repeat(16_000)))
        messages.put(JSONObject().put("role", "user").put("content", "next request"))
        session.compact(force = true)
        val history = session.snapshot()!!.messages
        assertEquals(setOf("mcp:one"), AgentMcpContext.openedGroupIds(history))
        assertFalse(history.any { it.toolCallsJson.contains(first.modelName) })
        assertEquals("next request", history.last().content)
        AgentModelClient.complete(config, "continue", AgentModelClient.ToolExecutor { error("No execution expected") },
            history = history, mcpProjection = snapshot, provider = provider { request, _ ->
                assertTrue(first.modelName in request.tools.names())
                assertFalse(second.modelName in request.tools.names())
                response("done")
            })
    }

    @Test
    fun variableSystemBoundaryAlsoWorksDuringAutomaticCompactionAfterLoading() {
        val oneServer = McpRunSnapshot(listOf(first))
        val opened = ConcurrentHashMap.newKeySet<String>()
        val loader = McpProgressiveLoader(oneServer, opened)
        var round = 0
        var summaries = 0
        val result = AgentModelClient.complete(config, "current request", AgentModelClient.ToolExecutor {
            if (it.name in McpProgressiveLoader.names) loader.execute(it) else AgentModelClient.ToolResult("actual MCP result")
        }, history = listOf(AgentModelClient.ConversationMessage("user", "old"),
            AgentModelClient.ConversationMessage("assistant", "x".repeat(20_000))),
            mcpProjection = oneServer, openedMcpGroupsProvider = { opened.toSet() },
            provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    response("short summary")
                } else when (++round) {
                    1 -> {
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 120_000)))
                        response(calls = listOf(call("tools_use_group", """{"group":"mcp:one"}""")))
                    }
                    2 -> {
                        assertTrue(first.modelName in request.tools.names())
                        assertTrue(request.messages.toString().contains("current request"))
                        response(calls = listOf(call(first.modelName, "{}")))
                    }
                    else -> response("done")
                }
            })
        assertEquals(1, summaries)
        val checkpoint = requireNotNull(result.contextSnapshot)
        assertEquals("current request", checkpoint.messages.first { it.role == "user" }.content)
        assertTrue(checkpoint.messages.none { it.role == "system" })
    }

    @Test
    fun offModeSendsFullCatalogOnEveryRoundWithoutLoaderOrIndex() {
        val full = JSONArray().also(snapshot::appendModelTools)
        var round = 0
        AgentModelClient.complete(config, "legacy", AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("ok") },
            additionalTools = full, provider = provider { request, _ ->
                assertTrue(request.tools.names().containsAll(listOf(first.modelName, second.modelName)))
                assertTrue(request.tools.names().intersect(McpProgressiveLoader.names).isEmpty())
                assertFalse(request.messages.objects().filter { it.optString("role") == "system" }
                    .any { it.optString("content").contains("mcp:one") })
                if (++round == 1) response(calls = listOf(call(first.modelName, "{}"))) else response("done")
            })
        assertEquals(2, round)
    }

    @Test
    fun profileFilteringHidesGroupsAndSearchNeverOpensFilteredMatches() {
        val opened = ConcurrentHashMap.newKeySet<String>()
        val loader = McpProgressiveLoader(snapshot, opened)
        var round = 0
        AgentModelClient.complete(config, "search", loader,
            mcpProjection = snapshot, openedMcpGroupsProvider = { opened.toSet() },
            toolFilter = { tools -> JSONArray(tools.objects().filter { it.getJSONObject("function").getString("name") == first.modelName }) },
            provider = provider { request, _ ->
                when (++round) {
                    1 -> {
                        assertEquals(McpProgressiveLoader.names, request.tools.names())
                        val index = request.messages.objects().last { it.optString("role") == "system" }.getString("content")
                        assertTrue(index.contains("mcp:one"))
                        assertFalse(index.contains("mcp:two"))
                        response(calls = listOf(call("tools_search", JSONObject().put("query", "select:${first.modelName},${second.modelName}").toString())))
                    }
                    else -> {
                        assertEquals(setOf("mcp:one"), opened)
                        assertEquals(setOf(first.modelName), request.tools.names())
                        assertFalse(request.messages.objects().last { it.optString("role") == "tool" }.getString("content").contains(second.modelName))
                        response("done")
                    }
                }
            })
    }

    @Test
    fun markerRecoveryIgnoresOrdinaryMessagesMalformedIdsAndRemovedServers() {
        val marker = AgentMcpContext.appendMarker("summary", setOf("mcp:one"))
        assertTrue(AgentMcpContext.openedGroupIds(listOf(AgentModelClient.ConversationMessage("user", marker))).isEmpty())
        assertTrue(AgentMcpContext.openedGroupIds(listOf(AgentModelClient.ConversationMessage("assistant", marker))).isEmpty())
        val malformed = AgentModelClient.ConversationMessage("assistant", "<!-- eta:opened_mcp_groups:[oops] -->", contextSummary = true)
        assertTrue(AgentMcpContext.openedGroupIds(listOf(malformed)).isEmpty())
        val unknown = AgentModelClient.ConversationMessage("assistant", AgentMcpContext.appendMarker("old", setOf("mcp:removed")), contextSummary = true)
        assertTrue(AgentMcpContext.compactedGroupIds(listOf(unknown), snapshot.deferredGroups()).isEmpty())
    }

    private fun tool(id: String, serverName: String, name: String): McpRunTool = McpRunTool(
        "mcp_${id}_${name}_12345678", McpServerSetting(id, serverName, "https://example.invalid/mcp"),
        McpToolDefinition(name, description = "Tool $name", inputSchemaJson = """{"type":"object"}"""), null,
    )
    private fun call(name: String, args: String, id: String = "call") = AgentModelClient.ToolCall(id, name, args)
    private fun callJson(call: AgentModelClient.ToolCall) = JSONObject().put("id", call.id).put("type", "function")
        .put("function", JSONObject().put("name", call.name).put("arguments", call.argumentsJson))
    private fun response(text: String = "", calls: List<AgentModelClient.ToolCall> = emptyList()) = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls")
            .also { if (calls.isNotEmpty()) it.put("tool_calls", JSONArray(calls.map(::callJson))) },
    )
    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "mcp-fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = block(request, onEvent)
    }
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.names() = objects().map { it.getJSONObject("function").getString("name") }.toSet()
    private fun code(result: AgentModelClient.ToolResult) = JSONObject(result.content).getString("code")
}
