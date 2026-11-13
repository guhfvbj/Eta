package io.github.mangi.eta.agent.subagent

import android.app.Application
import io.github.mangi.eta.agent.mcp.AgentToolSearchCatalog
import io.github.mangi.eta.agent.mcp.McpGroupCatalog
import io.github.mangi.eta.agent.mcp.McpProgressiveLoader
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpRunTool
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentLoop
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.AgentToolCatalog
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import io.github.mangi.eta.agent.model.EndpointKind
import io.github.mangi.eta.agent.model.ProviderCapabilities
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.prompt.AgentToolPolicyStore
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class SubagentTest {
    private val first = mcpTool("one", "Calendar", "findEvents")
    private val sibling = mcpTool("one", "Calendar", "deleteEvent")
    private val second = mcpTool("two", "Documents", "readDocument")
    private val mcpSnapshot = McpRunSnapshot(listOf(first, sibling, second))
    private val progressiveModes = listOf(Prefs.Keys.MCP_LOADING_MODE_SEARCH, Prefs.Keys.MCP_LOADING_MODE_GROUP)

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
    fun builtinProfilesEnforceReadOnlyAndForbidRecursiveOrGuiTools() {
        val fullCatalog = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            memoryTools = true,
            capabilities = AgentToolCapabilities(rootAvailable = true),
        ).also { it.put(AgentSubagentToolCatalog.schema()) }

        SubagentProfile.BUILTIN_PROFILES.forEach { profile ->
            val allowed = profile.filterTools(fullCatalog).toolNames()
            assertFalse("subagent" in allowed)
            assertFalse("observe_screen" in allowed)
            assertFalse("tap_element" in allowed)
            assertFalse("type_text" in allowed)
            assertFalse("browser_use" in allowed)
            assertFalse("memory_write" in allowed)
            assertTrue("read_file" in allowed)
            assertTrue("glob_files" in allowed)
            assertTrue("grep_files" in allowed)
            assertTrue("web_search" in allowed)
        }

        val exploreTools = SubagentProfile.EXPLORE.filterTools(fullCatalog).toolNames()
        assertFalse("write_file" in exploreTools)
        assertFalse("edit_file" in exploreTools)
        assertFalse("terminal" in exploreTools)

        val planTools = SubagentProfile.PLAN.filterTools(fullCatalog).toolNames()
        assertFalse("write_file" in planTools)
        assertFalse("edit_file" in planTools)
        assertFalse("terminal" in planTools)

        val coderTools = SubagentProfile.CODER.filterTools(fullCatalog).toolNames()
        assertTrue("write_file" in coderTools)
        assertTrue("edit_file" in coderTools)
        assertTrue("terminal" in coderTools)
    }

    @Test
    fun customSubagentProfileCanBeSavedDiscoveredAndFilteredSafely() {
        val tempDir = java.nio.file.Files.createTempDirectory("eta-subagent-test").toFile()
        try {
            val saved = SubagentProfileCatalog.saveCustomProfile(
                name = "bill-analyzer",
                description = "专门检索与汇总个人账单与订单",
                whenToUse = "当用户需要统计账单或查询订单时使用",
                tools = listOf("summarize_bills", "search_personal_orders", "observe_screen", "subagent"),
                prompt = "你是专门负责账单分析的 Subagent。",
                agentsDir = tempDir,
            )
            assertFalse(saved.builtin)
            assertEquals("bill-analyzer", saved.name)
            assertTrue(saved.isToolAllowed("summarize_bills"))
            assertTrue(saved.isToolAllowed("search_personal_orders"))
            assertFalse(saved.isToolAllowed("observe_screen"))
            assertFalse(saved.isToolAllowed("subagent"))
            assertFalse(saved.isToolAllowed("subagent_profile"))
            assertFalse(saved.isToolAllowed("write_file"))

            val all = SubagentProfileCatalog.listAll(tempDir)
            assertTrue(all.any { it.name == "bill-analyzer" && !it.builtin })
            val schemaText = AgentSubagentToolCatalog.schema(all).toString()
            assertTrue(schemaText.contains("bill-analyzer"))

            assertTrue(SubagentProfileCatalog.deleteCustomProfile("bill-analyzer", tempDir))
            assertFalse(SubagentProfileCatalog.listAll(tempDir).any { it.name == "bill-analyzer" })

            // 未保存在磁盘的任意 subagent_type 可直接当场生成临时专职 Subagent 运行，不落盘
            val ephemeral = SubagentProfile.resolve(
                name = "ad-hoc-auditor",
                catalog = SubagentProfileCatalog.listAll(tempDir),
                customRole = "专门核对近三天通知与日程冲突",
                customTools = listOf("search_calendar_events", "recent_notifications", "observe_screen"),
            )
            assertEquals("ad-hoc-auditor", ephemeral.name)
            assertTrue(ephemeral.isToolAllowed("search_calendar_events"))
            assertTrue(ephemeral.isToolAllowed("recent_notifications"))
            assertFalse(ephemeral.isToolAllowed("observe_screen"))
            assertFalse(ephemeral.isToolAllowed("write_file"))
            assertFalse(SubagentProfileCatalog.listAll(tempDir).any { it.name == "ad-hoc-auditor" })
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun ephemeralAndCoderSubagentsLoadMcpOnNextRoundWithoutUsingParentLoader() {
        for (mode in progressiveModes) {
            for ((type, tools) in listOf(
                "custom-worker" to null,
                "custom-worker" to listOf("*"),
                SubagentProfile.TYPE_CODER to null,
            )) {
                val key = UUID.randomUUID().toString()
                val parentOpened = ConcurrentHashMap.newKeySet<String>().also { it.add("mcp:two") }
                val parentLoader = McpProgressiveLoader(mcpSnapshot, parentOpened)
                val executed = mutableListOf<String>()
                var round = 0
                val executor = subagentExecutor(key, mode, provider { request ->
                    val names = request.tools.toolNames()
                    when (++round) {
                        1 -> {
                            assertTrue(names.none { it.startsWith("mcp_") })
                            assertEquals(loaderNames(mode), names.intersect(McpProgressiveLoader.names))
                            assertTrue(request.mcpIndex().contains("mcp:one"))
                            assertTrue(request.mcpIndex().contains("mcp:two"))
                            assertEquals(mode == Prefs.Keys.MCP_LOADING_MODE_SEARCH,
                                request.mcpIndex().contains(AgentToolSearchCatalog.SEARCH))
                            response(loadCall(mode))
                        }
                        2 -> {
                            assertEquals(setOf(first.modelName, sibling.modelName), names.filter { it.startsWith("mcp_") }.toSet())
                            assertEquals(setOf("mcp:one"), session(key).openedMcpGroups)
                            assertEquals(setOf("mcp:two"), parentOpened)
                            assertFalse(request.mcpIndex().contains("mcp:one"))
                            assertTrue(request.mcpIndex().contains("mcp:two"))
                            assertTrue(request.toolContents().any { it.contains("next_turn") })
                            response(toolCall(first.modelName))
                        }
                        else -> {
                            assertTrue(request.toolContents().contains("actual MCP result"))
                            response()
                        }
                    }
                }, AgentModelClient.ToolExecutor { call ->
                    executed += call.name
                    if (call.name in McpProgressiveLoader.names) parentLoader.execute(call)
                    else AgentModelClient.ToolResult("actual MCP result")
                })
                val result = executeSubagent(executor, type, tools)
                assertEquals(3, round)
                assertEquals(listOf(first.modelName), executed)
                assertEquals(setOf("mcp:two"), parentOpened)
                assertEquals(setOf("mcp:one"), session(key).openedMcpGroups)
                if (type == "custom-worker") assertEquals(setOf("*"), session(key).profile.explicitTools)
                else assertFalse(session(key).profile.isToolAllowed(McpGroupCatalog.USE_GROUP))
                assertEquals("done", result.getString("summary"))
            }
        }
    }

    @Test
    fun resumeRetainsLoaderOnlyGroupsAcrossExecutorsAndKeepsSiblingSessionsIndependent() {
        for (mode in progressiveModes) {
            val key = UUID.randomUUID().toString()
            var round = 0
            val firstExecutor = subagentExecutor(key, mode, provider { request ->
                if (++round == 1) response(loadCall(mode)) else {
                    assertTrue(first.modelName in request.tools.toolNames())
                    response()
                }
            })
            val original = executeSubagent(firstExecutor).getString("subagent_id")
            assertEquals(2, round)
            val originalEntry = session(key, original)
            assertEquals(setOf("mcp:one"), originalEntry.openedMcpGroups)
            assertTrue(AgentModelClient.historyToolNames(originalEntry.history).none { it.startsWith("mcp_") })

            // 只调用过加载器，尚无 MCP 工具执行历史；新 Executor 必须复用 Entry 的加载状态。
            var resumedRounds = 0
            val resumedExecutor = subagentExecutor(key, mode, provider { request ->
                resumedRounds++
                assertTrue(first.modelName in request.tools.toolNames())
                assertFalse(second.modelName in request.tools.toolNames())
                assertTrue(request.toolContents().any { it.contains("next_turn") })
                response()
            })
            val resumed = executeSubagent(resumedExecutor, type = null, resume = original)
            assertEquals(original, resumed.getString("subagent_id"))
            assertEquals(1, resumedRounds)

            var siblingRounds = 0
            val siblingExecutor = subagentExecutor(key, mode, provider { request ->
                if (++siblingRounds == 1) {
                    assertTrue(request.tools.toolNames().none { it.startsWith("mcp_") })
                    assertTrue(request.mcpIndex().contains("mcp:one"))
                    response(toolCall(McpGroupCatalog.USE_GROUP, """{"group":"mcp:two"}"""))
                } else {
                    assertEquals(setOf(second.modelName), request.tools.toolNames().filter { it.startsWith("mcp_") }.toSet())
                    response()
                }
            })
            val other = executeSubagent(siblingExecutor).getString("subagent_id")
            assertEquals(2, siblingRounds)
            assertFalse(original == other)
            assertEquals(setOf("mcp:one"), originalEntry.openedMcpGroups)
            assertEquals(setOf("mcp:two"), session(key, other).openedMcpGroups)
            assertFalse(originalEntry.running.get())
            assertFalse(session(key, other).running.get())
            executeSubagent(resumedExecutor, type = null, resume = original)
            assertEquals(2, resumedRounds)
        }
    }

    @Test
    fun coderPolicyAndCustomExplicitToolsPermitLoadersButHideDisallowedMcpToolsAndServers() {
        for (mode in progressiveModes) {
            for (type in listOf(SubagentProfile.TYPE_CODER, "custom-worker")) {
                val key = UUID.randomUUID().toString()
                val tools = if (type == SubagentProfile.TYPE_CODER) null else listOf(first.modelName)
                val policy = if (tools == null) {
                    AgentToolPolicy(ToolPolicyMode.ALLOW_LIST, setOf(first.modelName))
                } else AgentToolPolicy()
                assertTrue(AgentToolPolicyStore.save(policy))
                var round = 0
                val executed = mutableListOf<String>()
                val executor = subagentExecutor(key, mode, provider { request ->
                    val names = request.tools.toolNames()
                    when (++round) {
                        1 -> {
                            assertEquals(loaderNames(mode), names)
                            assertTrue(request.mcpIndex().contains("mcp:one"))
                            assertFalse(request.mcpIndex().contains("mcp:two"))
                            assertFalse(request.mcpIndex().contains(sibling.definition.name))
                            assertFalse(request.mcpIndex().contains(second.server.name))
                            assertFalse(session(key).profile.isToolAllowed(McpGroupCatalog.USE_GROUP))
                            assertFalse(session(key).profile.isToolAllowed(AgentToolSearchCatalog.SEARCH))
                            response(toolCall(McpGroupCatalog.USE_GROUP, """{"group":"mcp:two"}"""))
                        }
                        2 -> {
                            assertTrue(request.toolContents().any { it.contains("MCP_GROUP_NOT_AVAILABLE") })
                            assertTrue(session(key).openedMcpGroups.isEmpty())
                            if (mode == Prefs.Keys.MCP_LOADING_MODE_SEARCH) {
                                response(toolCall(AgentToolSearchCatalog.SEARCH, """{"query":"Documents"}"""))
                            } else response(loadCall(mode))
                        }
                        3 -> {
                            if (mode == Prefs.Keys.MCP_LOADING_MODE_SEARCH) {
                                assertTrue(request.toolContents().any { it.contains("NO_MATCHING_TOOLS") })
                                assertTrue(session(key).openedMcpGroups.isEmpty())
                                response(toolCall(AgentToolSearchCatalog.SEARCH, JSONObject()
                                    .put("query", "select:${first.modelName},${sibling.modelName},${second.modelName}").toString()))
                            } else {
                                assertOnlyFirstMcpTool(request, key)
                                response(toolCall(first.modelName))
                            }
                        }
                        4 -> {
                            if (mode == Prefs.Keys.MCP_LOADING_MODE_SEARCH) {
                                assertOnlyFirstMcpTool(request, key)
                                val receipt = request.toolContents().last()
                                assertFalse(receipt.contains(sibling.modelName))
                                assertFalse(receipt.contains(second.modelName))
                                response(toolCall(first.modelName))
                            } else response()
                        }
                        else -> response()
                    }
                }, AgentModelClient.ToolExecutor { call ->
                    executed += call.name
                    AgentModelClient.ToolResult("actual MCP result")
                })
                executeSubagent(executor, type, tools)
                assertEquals(if (mode == Prefs.Keys.MCP_LOADING_MODE_SEARCH) 5 else 4, round)
                assertEquals(listOf(first.modelName), executed)
                assertEquals(setOf("mcp:one"), session(key).openedMcpGroups)
            }
        }
    }

    @Test
    fun emptyToolPolicyHidesMcpFromWildcardSubagentsInBothProgressiveModes() {
        assertTrue(AgentToolPolicyStore.save(AgentToolPolicy(ToolPolicyMode.ALLOW_LIST)))
        for (mode in progressiveModes) {
            val key = UUID.randomUUID().toString()
            var rounds = 0
            val executor = subagentExecutor(key, mode, provider { request ->
                rounds++
                assertEquals(0, request.tools.length())
                assertEquals("", request.mcpIndex())
                response()
            })
            executeSubagent(executor)
            assertEquals(1, rounds)
            assertEquals(setOf("*"), session(key).profile.explicitTools)
            assertTrue(session(key).openedMcpGroups.isEmpty())
        }
    }

    @Test
    fun readOnlyProfilesPublishNeitherMcpToolsNorLoadersAndRejectHallucinatedCalls() {
        for (mode in progressiveModes) {
            for (type in listOf(SubagentProfile.TYPE_EXPLORE, SubagentProfile.TYPE_PLAN, "custom-worker")) {
                val key = UUID.randomUUID().toString()
                var round = 0
                val executor = subagentExecutor(key, mode, provider { request ->
                    val names = request.tools.toolNames()
                    assertTrue(names.none { it.startsWith("mcp_") })
                    assertTrue(names.intersect(McpProgressiveLoader.names).isEmpty())
                    assertEquals("", request.mcpIndex())
                    if (++round == 1) response(
                        toolCall(McpGroupCatalog.USE_GROUP, """{"group":"mcp:one"}""", "group"),
                        toolCall(AgentToolSearchCatalog.SEARCH, """{"query":"Calendar"}""", "search"),
                        toolCall(first.modelName, id = "mcp"),
                    ) else {
                        assertEquals(3, request.toolContents().size)
                        assertTrue(request.toolContents().all { it.contains("TOOL_NOT_AVAILABLE") })
                        response()
                    }
                })
                executeSubagent(executor, type, if (type == "custom-worker") listOf("read_file") else null)
                assertEquals(2, round)
                assertTrue(session(key).openedMcpGroups.isEmpty())
            }
        }
    }

    @Test
    fun offModeEagerlyPublishesOnlyPermittedMcpToolsAndNeverMountsLoaders() {
        for (type in listOf("custom-worker", SubagentProfile.TYPE_CODER, "limited-worker",
            SubagentProfile.TYPE_EXPLORE, SubagentProfile.TYPE_PLAN)) {
            val key = UUID.randomUUID().toString()
            val expected = when (type) {
                "custom-worker", SubagentProfile.TYPE_CODER -> setOf(first.modelName, sibling.modelName, second.modelName)
                "limited-worker" -> setOf(first.modelName)
                else -> emptySet()
            }
            var round = 0
            val executed = mutableListOf<String>()
            val executor = subagentExecutor(key, Prefs.Keys.MCP_LOADING_MODE_OFF, provider { request ->
                val names = request.tools.toolNames()
                assertEquals(expected, names.filter { it.startsWith("mcp_") }.toSet())
                assertTrue(names.intersect(McpProgressiveLoader.names).isEmpty())
                assertEquals("", request.mcpIndex())
                when (++round) {
                    1 -> if (expected.isNotEmpty()) response(toolCall(first.modelName)) else response()
                    2 -> response(toolCall(McpGroupCatalog.USE_GROUP, """{"group":"mcp:one"}"""))
                    else -> {
                        assertTrue(request.toolContents().last().contains("TOOL_NOT_AVAILABLE"))
                        response()
                    }
                }
            }, AgentModelClient.ToolExecutor { call ->
                executed += call.name
                AgentModelClient.ToolResult("actual MCP result")
            })
            executeSubagent(executor, type, if (type == "limited-worker") listOf(first.modelName) else null)
            assertEquals(if (expected.isEmpty()) 1 else 3, round)
            assertEquals(if (expected.isEmpty()) emptyList<String>() else listOf(first.modelName), executed)
            assertTrue(session(key).openedMcpGroups.isEmpty())
        }
    }

    @Test
    fun agentLoopExecutesMultipleSubagentCallsInParallelAndPreservesOrder() {
        val startedLatch = CountDownLatch(2)
        val releaseLatch = CountDownLatch(1)
        val events = mutableListOf<AgentEvent>()

        val provider = object : AgentProviderClient {
            override val id: String = "fake-provider"
            override val capabilities = ProviderCapabilities(
                endpoint = EndpointKind.CHAT_COMPLETIONS,
                streamingText = true,
                streamingToolCalls = true,
                imageInput = false,
                toolResultImages = false,
                strictTools = false,
                parallelToolCalls = true,
            )
            private var callCount = 0
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ): ProviderResponse {
                callCount += 1
                if (callCount == 1) {
                    val toolCalls = JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "call_1")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "subagent")
                                        .put(
                                            "arguments",
                                            JSONObject()
                                                .put("subagent_type", "explore")
                                                .put("description", "检索日历")
                                                .put("prompt", "查找本周日历")
                                                .toString(),
                                        ),
                                ),
                        )
                        .put(
                            JSONObject()
                                .put("id", "call_2")
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "subagent")
                                        .put(
                                            "arguments",
                                            JSONObject()
                                                .put("subagent_type", "coder")
                                                .put("description", "检查日志")
                                                .put("prompt", "分析错误日志")
                                                .toString(),
                                        ),
                                ),
                        )
                    return ProviderResponse(
                        assistantMessage = JSONObject()
                            .put("role", "assistant")
                            .put("content", "")
                            .put("finish_reason", "tool_calls")
                            .put("tool_calls", toolCalls),
                    )
                }
                return ProviderResponse(
                    assistantMessage = JSONObject()
                        .put("role", "assistant")
                        .put("content", "汇总完成")
                        .put("finish_reason", "stop"),
                )
            }
        }

        val tools = JSONArray().put(AgentSubagentToolCatalog.schema())
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "开始"))
        val loop = AgentLoop(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            messages = messages,
            tools = tools,
            provider = provider,
            toolExecutor = AgentModelClient.ToolExecutor { call ->
                startedLatch.countDown()
                // 只有两个调用真正并发进入时，startedLatch 才能归零。
                assertTrue(
                    "Multiple subagent tool calls in the same turn must run concurrently",
                    startedLatch.await(3, TimeUnit.SECONDS),
                )
                releaseLatch.countDown()
                val snapshot = SubagentActivitySnapshot(
                    subagentId = "sub_${call.id}",
                    subagentType = "explore",
                    description = call.id,
                    status = SubagentActivitySnapshot.STATUS_COMPLETED,
                    output = "result-${call.id}",
                ).encode()
                AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("tool_calls", 2)
                        .put("summary", "result-${call.id}")
                        .toString(),
                    command = snapshot,
                )
            },
            runController = AgentRunController(),
            traceFormatter = AgentTraceFormatter(),
            onEvent = { synchronized(events) { events += it } },
        )

        val result = loop.run()
        assertEquals("汇总完成", result.content)
        val toolMessages = (0 until messages.length())
            .map { messages.getJSONObject(it) }
            .filter { it.optString("role") == "tool" }
        assertEquals(listOf("call_1", "call_2"), toolMessages.map { it.optString("tool_call_id") })

        val finishedEvents = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(2, finishedEvents.size)
        finishedEvents.forEach { finished ->
            assertNotNull(SubagentActivitySnapshot.decode(finished.command))
        }
    }

    @Test
    fun subagentProgressEventRoundTripsThroughRuntimeWire() {
        val snapshot = SubagentActivitySnapshot(
            subagentId = "sub_1",
            subagentType = "explore",
            description = "检索近期便签",
            prompt = "查找最近 7 天的便签记录",
            model = "GPT-5.4 Mini",
            status = SubagentActivitySnapshot.STATUS_RUNNING,
            currentAction = "正在调用 search_notes",
            steps = listOf(
                SubagentStepSnapshot(
                    id = "tool-1-tc1",
                    kind = SubagentStepSnapshot.KIND_TOOL,
                    title = "查询便签",
                    detail = "",
                    status = SubagentStepSnapshot.STATUS_RUNNING,
                    toolName = "search_notes",
                ),
            ),
        )
        val event = AgentEvent.SubagentProgress(
            round = 2,
            toolCallId = "call_99",
            subagentId = "sub_1",
            subagentType = "explore",
            snapshotJson = snapshot.encode(),
        )
        val bundle = AgentRuntimeWire.eventToBundle(event)
        val decoded = AgentRuntimeWire.eventFromBundle(bundle) as? AgentEvent.SubagentProgress
        assertEquals(event, decoded)
        assertEquals(snapshot, SubagentActivitySnapshot.decode(decoded?.snapshotJson))
    }

    private fun subagentExecutor(
        conversationKey: String,
        mode: String,
        provider: AgentProviderClient,
        baseToolExecutor: AgentModelClient.ToolExecutor = AgentModelClient.ToolExecutor { call ->
            error("Unexpected base tool call: ${call.name}")
        },
    ) = SubagentExecutor(
        conversationKey = conversationKey,
        parentController = AgentRunController(),
        configProvider = {
            AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid/v1",
                apiKey = "fixture",
                model = "fixture",
                systemPrompt = "fixed",
                contextWindow = 128_000,
                browserTools = false,
            )
        },
        capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
        baseToolExecutor = baseToolExecutor,
        skillContext = SkillContext.EMPTY,
        memoryContext = AgentMemoryContext.DISABLED,
        mcpSnapshot = mcpSnapshot,
        mcpLoadingMode = mode,
        executionContext = null,
        onProgress = {},
        providerFor = { provider },
    )

    private fun executeSubagent(
        executor: SubagentExecutor,
        type: String? = "custom-worker",
        tools: List<String>? = null,
        resume: String? = null,
    ): JSONObject {
        val args = JSONObject().put("prompt", "use MCP").put("description", "MCP regression")
        if (type != null) args.put("subagent_type", type)
        if (tools != null) args.put("tools", JSONArray(tools))
        if (resume != null) args.put("resume", resume)
        val result = JSONObject(executor.execute(1, toolCall("subagent", args.toString())).content)
        assertTrue(result.toString(), result.optBoolean("ok"))
        return result
    }

    private fun session(key: String, id: String = "sub_1"): SubagentSessionStore.Entry =
        checkNotNull(SubagentSessionStore.get(key, id))

    private fun assertOnlyFirstMcpTool(request: ProviderRequest, key: String) {
        assertEquals(setOf(first.modelName), request.tools.toolNames())
        assertEquals(setOf("mcp:one"), session(key).openedMcpGroups)
        assertEquals("", request.mcpIndex())
    }

    private fun loaderNames(mode: String) = if (mode == Prefs.Keys.MCP_LOADING_MODE_SEARCH) {
        McpProgressiveLoader.names
    } else setOf(McpGroupCatalog.USE_GROUP)

    private fun loadCall(mode: String) = if (mode == Prefs.Keys.MCP_LOADING_MODE_SEARCH) {
        toolCall(AgentToolSearchCatalog.SEARCH, """{"query":"findEvents"}""")
    } else toolCall(McpGroupCatalog.USE_GROUP, """{"group":"mcp:one"}""")

    private fun mcpTool(server: String, label: String, name: String) = McpRunTool(
        "mcp_${server}_${name}_12345678",
        McpServerSetting(server, label, "https://example.invalid/mcp"),
        McpToolDefinition(name, description = "Tool $name", inputSchemaJson = """{"type":"object"}"""),
        null,
    )

    private fun toolCall(name: String, args: String = "{}", id: String = UUID.randomUUID().toString()) =
        AgentModelClient.ToolCall(id, name, args)

    private fun response(vararg calls: AgentModelClient.ToolCall) = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", if (calls.isEmpty()) "done" else "")
        .put("finish_reason", if (calls.isEmpty()) "stop" else "tool_calls")
        .also { message ->
            if (calls.isNotEmpty()) message.put("tool_calls", JSONArray(calls.map { call ->
                JSONObject().put("id", call.id).put("type", "function")
                    .put("function", JSONObject().put("name", call.name).put("arguments", call.argumentsJson))
            }))
        })

    private fun provider(block: (ProviderRequest) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "subagent-mcp-fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ) = block(request)
    }

    private fun ProviderRequest.mcpIndex() = (0 until messages.length()).map(messages::getJSONObject)
        .filter { it.optString("role") == "system" && it.optString("content").contains("mcp:") }
        .joinToString("\n") { it.getString("content") }

    private fun ProviderRequest.toolContents() = (0 until messages.length()).map(messages::getJSONObject)
        .filter { it.optString("role") == "tool" }.map { it.getString("content") }

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(linkedSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
