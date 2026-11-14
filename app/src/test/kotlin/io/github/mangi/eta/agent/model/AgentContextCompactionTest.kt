package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.mcp.McpDeferredGroup
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextCompactionTest {
    @Test
    fun automaticScreenContentIsNotCopiedIntoCompactionInput() {
        val messages = jsonHistory()
        val user = AgentConversationCodec.userTextMessage("保留最新问题")
        AssistantScreenContextProjection.attach(user, "PRIVATE_AUTOMATIC_SCREEN_CONTENT")
        messages.put(user)
        val compacted = AgentContextCompactor(config, provider { request, _ ->
            assertFalse(request.messages.toString().contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
            response("已完成此前的任务。")
        }, AgentRunController()).compact(messages, 1, emptySet())
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(
            AgentConversationCodec.transcript(compacted, 1),
        ).contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
        assertTrue(AssistantScreenContextProjection.project(compacted).toString().contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
    }

    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        contextWindow = 128_000,
    )

    @Test
    fun largeResumedHistoryCompactsBeforeItsFirstRequestWithoutUsage() {
        val original = "历史事实".repeat(40_000)
        var summaries = 0
        var requests = 0
        val result = AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = listOf(AgentModelClient.ConversationMessage("assistant", original)),
            provider = provider { request, _ ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    response("保留此前事实。")
                } else {
                    requests++
                    assertTrue(summaries > 0)
                    assertFalse(request.messages.toString().contains(original))
                    response("完成")
                }
            })
        assertEquals(1, requests)
        assertNotNull(result.contextSnapshot!!.lastContextTokens)
    }

    @Test
    fun interruptedToolBatchKeepsCompletedResultAndMarksOnlyUnknownResults() {
        val call = AgentModelClient.ConversationMessage("assistant", "", toolCallsJson = """[
            {"id":"a","type":"function","function":{"name":"fixture","arguments":"{}"}},
            {"id":"b","type":"function","function":{"name":"fixture","arguments":"{}"}}]""")
        val completed = AgentModelClient.ConversationMessage("tool", "真实完成结果", toolCallId = "a")
        val restored = AgentToolBatchRecovery.completeInterrupted(listOf(call, completed))
        assertEquals(completed, restored[1])
        assertEquals("b", restored.last().toolCallId)
        assertTrue(restored.last().content.contains("TOOL_INTERRUPTED"))
        assertEquals(restored, AgentToolBatchRecovery.completeInterrupted(restored))
    }

    @Test
    fun automaticCompactionPreservesIncrementalTranscriptAndLatestRequest() {
        val history = (1..8).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "第 $turn 个任务"),
            AgentModelClient.ConversationMessage("assistant", "事实 $turn ".repeat(2200)),
        ) }
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                assertEquals(0, request.effectiveTools.length())
                response("已完成前面的任务，继续处理最新请求。")
            } else {
                assertTrue(request.messages.toString().contains("最新请求必须保留"))
                assertTrue(request.messages.toString().contains("固定约束"))
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 45_000)))
                response("完成最新请求")
            }
        }
        val result = AgentModelClient.complete(
            config.copy(contextWindow = 50_000), "最新请求必须保留", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history, provider = provider, onContextSnapshot = snapshots::add, onEvent = events::add,
        )
        assertTrue(summaries > 0)
        assertEquals(listOf("完成最新请求"), result.transcript.map { it.content })
        val snapshot = checkNotNull(result.contextSnapshot)
        assertTrue(snapshot.messages.any { it.contextSummary })
        assertEquals("完成最新请求", snapshot.messages.last().content)
        assertTrue(snapshot.messages.any { it.role == "user" && it.content == "最新请求必须保留" })
        assertEquals(9, snapshot.consumedUserTurns)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "completed" })
        assertTrue(snapshots.all { AgentContextSnapshot.decode(it.encode()) == it })
    }

    @Test
    fun manualCompactionDoesNotCreateUserOrAssistantTranscript() {
        val result = AgentModelClient.complete(
            config, "", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), compactOnly = true, provider = provider { request, _ ->
                assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
                response("历史工作已完成，保留后续问题。")
            },
        )
        assertTrue(result.transcript.isEmpty())
        assertEquals("", result.content)
        assertNotNull(result.contextSnapshot)
        assertTrue(result.contextSnapshot!!.messages.none { it.role == "user" && it.content.isBlank() })
    }

    @Test
    fun truncatedSummaryNeverReplacesOriginalContext() {
        val original = jsonHistory()
        val before = original.toString()
        val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
            response("半截摘要", "length")
        }, AgentRunController(), { emptySet() }, {}, { fail("不应提交快照") })
        assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
        assertEquals(before, original.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun cancellationAndPersistenceFailureKeepOriginalContext() {
        for (cancel in listOf(true, false)) {
            val original = jsonHistory()
            val before = original.toString()
            val controller = AgentRunController()
            val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
                if (cancel) controller.cancel()
                response("有界摘要")
            }, controller, { emptySet() }, {}, { throw IllegalStateException("fixture storage failure") })
            assertThrows(Exception::class.java) { session.compact(force = true) }
            assertEquals(before, original.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun summaryInputRedactsSensitiveToolsAndOpaqueReasoningAndImages() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "规则"))
        messages.put(AgentConversationCodec.userTextMessage("旧任务"))
        val assistant = JSONObject().put("role", "assistant").put("content", "读取结果")
            .put("reasoning_content", "PRIVATE_THINKING")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "sensitive").put("type", "function")
                .put("function", JSONObject().put("name", "read_secret").put("arguments", "SECRET_ARGUMENT"))))
        ResponsesEphemeralState.attachOutputItems(assistant, JSONArray().put(JSONObject().put("encrypted", "OPAQUE_SECRET")))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "sensitive").put("content", "SECRET_RESULT"))
        history().forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        val pending = AgentConversationCodec.userMessage("瞬时观察", listOf(
            AgentModelClient.ModelImage("data:image/png;base64,PRIVATE_IMAGE", "image/png", 10),
        )).put("_eta_observation", true)
        messages.put(pending)
        val compacted = AgentContextCompactor(config, provider { request, _ ->
            val text = request.messages.toString()
            listOf("SECRET_ARGUMENT", "SECRET_RESULT", "PRIVATE_THINKING", "OPAQUE_SECRET", "PRIVATE_IMAGE").forEach {
                assertFalse("摘要包含敏感数据 $it", text.contains(it))
            }
            response("已读取过信息，原始敏感结果未保留。")
        }, AgentRunController()).compact(messages, 1, setOf("sensitive"))
        assertSame(pending, compacted.getJSONObject(compacted.length() - 1))
        assertFalse(compacted.toString().contains("OPAQUE_SECRET"))
    }

    @Test
    fun toolBatchCannotBeSplitUntilEveryResultIsPresent() {
        val call = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().apply {
            listOf("a", "b").forEach { put(JSONObject().put("id", it).put("type", "function")
                .put("function", JSONObject().put("name", "fixture").put("arguments", "{}"))) }
        })
        val messages = listOf(call,
            JSONObject().put("role", "tool").put("tool_call_id", "a"),
            JSONObject().put("role", "tool").put("tool_call_id", "b"),
            AgentConversationCodec.userTextMessage("继续"))
        assertFalse(AgentContextCompactor.canSplit(messages, 1))
        assertFalse(AgentContextCompactor.canSplit(messages, 2))
        assertTrue(AgentContextCompactor.canSplit(messages, 3))
    }

    @Test
    fun signedAnthropicToolRoundCannotCompactBeforeReturningToolResults() {
        val messages = jsonHistory()
        messages.put(AgentConversationCodec.userTextMessage("继续查询"))
        val assistant = JSONObject().put("role", "assistant").put("content", "")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "toolu_current")
                .put("type", "function").put("function", JSONObject().put("name", "device_info")
                    .put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "")
                .put("signature", "CURRENT_SIGNATURE"))
            .put(JSONObject().put("type", "tool_use").put("id", "toolu_current")
                .put("name", "device_info").put("input", JSONObject())))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_current")
            .put("content", "工具结果"))
        val original = messages.toString()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            fail("签名未用前不能改写历史")
            response("不应执行")
        }, AgentRunController(), { emptySet() }, {}, { fail("不应提交快照") })

        val failure = assertThrows(AgentModelFailure::class.java) {
            session.compact(force = true)
        }
        assertEquals("ANTHROPIC_THINKING_CONTEXT_LOCKED", failure.code)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun compactionDropsCompletedAnthropicSignaturesAfterChangingPrefix() {
        val messages = jsonHistory()
        messages.put(AgentConversationCodec.userTextMessage("已完成的工具任务"))
        val assistant = JSONObject().put("role", "assistant").put("content", "读取中")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "toolu_old")
                .put("type", "function").put("function", JSONObject().put("name", "device_info")
                    .put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "")
                .put("signature", "STALE_SIGNATURE"))
            .put(JSONObject().put("type", "tool_use").put("id", "toolu_old")
                .put("name", "device_info").put("input", JSONObject())))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_old")
            .put("content", "完成"))
        messages.put(JSONObject().put("role", "assistant").put("content", "查询完成"))
        messages.put(AgentConversationCodec.userTextMessage("新的问题"))

        val compacted = AgentContextCompactor(config, provider { _, _ ->
            response("此前任务已完成。")
        }, AgentRunController()).compact(messages, 1, emptySet())

        assertFalse(compacted.toString().contains("STALE_SIGNATURE"))
        assertTrue(compacted.toString().contains("新的问题"))
        assertTrue(compacted.toString().contains("toolu_old")) // retained closed exchange, without its stale signature
    }

    @Test
    fun overflowClassificationDoesNotTreatEveryBadRequestAsCapacityFailure() {
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"code":"context_length_exceeded","message":"private fixture"}}""").code)
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"type":"invalid_request_error","message":"prompt is too long: fixture"}}""").code)
        assertEquals("HTTP_400", AgentModelFailure.http(400,
            """{"error":{"message":"invalid tool parameter"}}""").code)
    }

    @Test
    fun explicitOverflowCompactsAndRetriesWithoutFailedAttemptReasoning() {
        var normalRequests = 0
        var summaryRequests = 0
        val result = AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaryRequests++
                    response("之前的任务已完成。")
                } else if (normalRequests++ == 0) {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "失败思考"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                } else response("完成")
            })
        assertEquals("完成", result.content)
        assertEquals(2, normalRequests)
        assertEquals(1, summaryRequests)
        assertEquals("", result.reasoningContent)
        assertEquals(listOf("完成"), result.transcript.map { it.content })
    }

    @Test
    fun hostedToolOverflowDoesNotReplayOrStartCompaction() {
        var requests = 0
        val error = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "查询", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.HostedToolStarted("hosted", "web_search"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                })
        }
        assertEquals(1, requests)
        assertFalse((error.cause as AgentModelFailure).retryable)
    }

    @Test
    fun summaryRequestIncludesOnlyCompactedPrefixAndRetainsRecentTurns() {
        var requests = 0
        val seen = mutableListOf<String>()
        val result = AgentContextCompactor(config, provider { request, _ ->
            requests++
            val historyText = request.messages.getJSONObject(1).getString("content")
            seen += historyText
            response("之前的工作已完成。")
        }, AgentRunController()).compact(jsonHistory(), 1, emptySet())
        assertEquals(1, requests)
        for (turn in 1..4) assertTrue(seen.any { it.contains("问题 $turn") })
        for (turn in 5..6) {
            assertFalse(seen.any { it.contains("问题 $turn") })
            assertTrue(result.toString().contains("问题 $turn"))
        }
    }

    @Test
    fun completedAnswerRemainsSuccessfulWhenFinalCompactionFails() {
        val events = mutableListOf<AgentEvent>()
        val finalText = "完整结果".repeat(17_000)
        val result = AgentModelClient.complete(config.copy(contextWindow = 80_000), "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) response("不完整", "length")
                else {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 70_000)))
                    response(finalText)
                }
            }, onEvent = events::add)
        assertEquals(finalText, result.content)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "failed" })
        assertNotNull(result.contextSnapshot)
        assertEquals(finalText, result.contextSnapshot!!.messages.last().content)
        assertEquals(finalText, result.transcript.last().content)
    }

    @Test
    fun compactionPreservesTwoRecentClosedFileExchangesAndCountsOnlyPrefixUsers() {
        val source = (1..6).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "inspect $turn"),
            AgentModelClient.ConversationMessage("assistant", toolCallsJson = """[{"id":"read-$turn","type":"function","function":{"name":"read_file","arguments":"{}"}}]"""),
            AgentModelClient.ConversationMessage("tool", "file $turn line 10 expected_revision=hash-$turn\n" + "code $turn\n".repeat(3_000),
                toolCallId = "read-$turn"),
            AgentModelClient.ConversationMessage("assistant", "found $turn"),
        ) }
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "rules"))
        source.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        val result = AgentContextCompactor(config, provider { _, _ -> response("older findings") }, AgentRunController())
            .compact(messages, 1, emptySet())
        val history = AgentConversationCodec.transcript(result, 1)
        val summary = history.first()
        assertTrue(summary.contextSummary)
        assertTrue(summary.compactedUserTurns in 1..4)
        assertEquals(summary.compactedUserTurns, summary.summaryThroughUserTurn)
        assertEquals(6, history.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 })
        for (turn in 5..6) assertTrue(history.contains(source.first { it.toolCallId == "read-$turn" }))
        assertTrue(AgentContextCompactor.canSplit((1 until result.length()).map(result::getJSONObject), result.length() - 1))
        assertTrue(result.toString().length < messages.toString().length)
    }

    @Test
    fun nonReducingTailStepsDownAndSingleHugeTurnProtectsLatestUser() {
        var requests = 0
        val source = listOf(
            AgentModelClient.ConversationMessage("user", "old"), AgentModelClient.ConversationMessage("assistant", "ok"),
            AgentModelClient.ConversationMessage("user", "middle"), AgentModelClient.ConversationMessage("assistant", "ok"),
            AgentModelClient.ConversationMessage("user", "latest"), AgentModelClient.ConversationMessage("assistant", "x".repeat(30_000)),
        )
        val messages = JSONArray(source.map(AgentConversationCodec::toJsonObject))
        val result = AgentContextCompactor(config, provider { _, _ -> requests++; response("s".repeat(1_000)) }, AgentRunController())
            .compact(messages, 0, emptySet())
        assertTrue(requests > 1)
        val summary = AgentConversationCodec.fromJsonObject(result.getJSONObject(0))
        assertEquals(2, summary.compactedUserTurns)
        assertEquals(3, summary.summaryThroughUserTurn)
        assertEquals("latest", result.getJSONObject(1).getString("content"))
        assertTrue(result.toString().length < messages.toString().length)

        val single = JSONArray(source.takeLast(2).map(AgentConversationCodec::toJsonObject))
        val compacted = AgentContextCompactor(config, provider { _, _ -> response("bounded") }, AgentRunController())
            .compact(single, 0, emptySet())
        assertFalse(compacted.getJSONObject(0).has("_eta_compacted_users"))
        assertEquals(1, compacted.getJSONObject(0).getInt("_eta_summary_through_user"))
        assertEquals("latest", compacted.getJSONObject(1).getString("content"))
    }

    @Test
    fun retainedTailDropsOpaqueProviderStateReasoningAndSensitiveResults() {
        val messages = jsonHistory()
        val call = JSONObject().put("role", "assistant").put("content", "tail")
            .put("reasoning_content", "PRIVATE_REASONING")
            .put("tool_calls", JSONArray("""[{"id":"tail-secret","type":"function","function":{"name":"fixture","arguments":"PRIVATE_ARGS"}}]"""))
        ResponsesEphemeralState.attachOutputItems(call, JSONArray().put(JSONObject().put("opaque", "PRIVATE_RESPONSES")))
        AnthropicEphemeralState.attachContentBlocks(call, JSONArray().put(JSONObject().put("type", "thinking")
            .put("thinking", "PRIVATE_ANTHROPIC").put("signature", "PRIVATE_SIGNATURE")))
        GeminiEphemeralState.attachSteps(call, JSONArray().put(JSONObject().put("type", "thought").put("text", "PRIVATE_GEMINI")))
        messages.put(AgentConversationCodec.userTextMessage("tail user"))
        messages.put(call)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "tail-secret").put("content", "PRIVATE_RESULT"))
        messages.put(JSONObject().put("role", "assistant").put("content", "closed"))
        val result = AgentContextCompactor(config, provider { _, _ -> response("summary") }, AgentRunController())
            .compact(messages, 1, setOf("tail-secret"))
        assertTrue(result.toString().contains("tail-secret"))
        assertFalse(result.toString().contains("PRIVATE_"))
    }

    @Test
    fun summaryMcpMetadataComesOnlyFromCompactedPrefix() {
        val groups = listOf(McpDeferredGroup("mcp:old", "old", setOf("mcp_old"), "Old"),
            McpDeferredGroup("mcp:tail", "tail", setOf("mcp_tail"), "Tail"))
        val source = (1..3).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "task $turn"),
            AgentModelClient.ConversationMessage("assistant", toolCallsJson = """[{"id":"call-$turn","function":{"name":"${if (turn == 1) "mcp_old" else "mcp_tail"}","arguments":"{}"}}]"""),
            AgentModelClient.ConversationMessage("tool", "result", toolCallId = "call-$turn"),
            AgentModelClient.ConversationMessage("assistant", "facts $turn ".repeat(3_000)),
        ) }
        val result = AgentContextCompactor(config, provider { _, _ -> response("older findings") }, AgentRunController(),
            dynamicMcpGroupsProvider = { groups }).compact(JSONArray(source.map(AgentConversationCodec::toJsonObject)), 0, emptySet())
        val history = AgentConversationCodec.transcript(result, 0)
        assertEquals(setOf("mcp:old"), AgentMcpContext.openedGroupIds(history))
        assertTrue("mcp_tail" in AgentModelClient.historyToolNames(history))
        assertEquals(1, history.first().compactedUserTurns)
        assertEquals(1, history.first().summaryThroughUserTurn)
    }

    @Test
    fun summarizerUsesPlainTextAndBoundsHugeContentsAndArgumentsWithHeadAndTail() {
        val output = "HEAD file.kt:10 expected_revision=abc\n" + "body\n".repeat(100_000) + "TAIL file.kt:999"
        val history = listOf(
            AgentModelClient.ConversationMessage("assistant", "line one\nline two \"quoted\"", reasoningContent = "PRIVATE_REASONING",
                toolCallsJson = """[{"id":"read","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"file.kt\"}"}}]"""),
            AgentModelClient.ConversationMessage("tool", output, toolCallId = "read"),
        )
        val summarizer = AgentContextSummarizer(config, provider { request, _ ->
            val text = request.messages.getJSONObject(1).getString("content")
            assertTrue(text.contains("[assistant]\nline one\nline two \"quoted\""))
            assertTrue(text.contains("[tool_call read_file id=read]\n{\"path\":\"file.kt\"}"))
            assertTrue(text.contains("HEAD file.kt:10 expected_revision=abc"))
            assertTrue(text.contains("TAIL file.kt:999"))
            assertTrue(text.contains("已截断"))
            assertFalse(text.contains("PRIVATE_REASONING"))
            assertTrue(text.length < 7_000)
            response("bounded")
        }, AgentRunController(), false)
        assertEquals("bounded", summarizer.summarize(history))
    }

    @Test
    fun largeFormattedHistoryUsesBoundedRollingChunksAtMessageBoundaries() {
        var calls = 0
        val seen = mutableListOf<String>()
        val source = (1..90).map { AgentModelClient.ConversationMessage("tool", "message-$it\n" + "code\n".repeat(2_000)) }
        val summary = AgentContextSummarizer(config, provider { request, _ ->
            val text = request.messages.getJSONObject(1).getString("content")
            assertTrue(text.length < 144_000)
            if (calls > 0) assertTrue(text.contains("rolling-$calls"))
            assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
            assertEquals(0, request.effectiveTools.length())
            seen += text
            response("rolling-${++calls}")
        }, AgentRunController(), false).summarize(source)
        assertTrue(calls > 1)
        assertEquals("rolling-$calls", summary)
        for (index in 1..90) assertEquals(1, seen.count { it.contains("message-$index\n") })
    }

    @Test
    fun summarizationLowersEffortWithinProtocolAndModelCapabilities() {
        for ((type, source) in listOf(ProviderTypes.GEMINI to ProviderSourceTypes.GEMINI,
            ProviderTypes.OPENAI_COMPATIBLE to ProviderSourceTypes.DEEPSEEK,
            ProviderTypes.ANTHROPIC to ProviderSourceTypes.ANTHROPIC)) {
            val efforts = if (type == ProviderTypes.GEMINI) listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH)
                else listOf(ReasoningEffort.MINIMAL, ReasoningEffort.LOW, ReasoningEffort.HIGH)
            val configured = config.copy(providerType = type, providerSourceType = source, thinkingEnabled = true,
                reasoningEffort = ReasoningEffort.HIGH, reasoningCapabilities = ModelReasoningCapabilities(
                    supportedEfforts = efforts, mandatory = true))
            AgentContextSummarizer(configured, provider { request, _ ->
                assertEquals(ReasoningEffort.LOW, request.config.effectiveReasoningEffort)
                when (type) {
                    ProviderTypes.GEMINI -> GeminiInteractionsRequest.build(request.effectiveConfig, request.messages, JSONArray())
                    ProviderTypes.ANTHROPIC -> ProviderReasoning.applyAnthropicRequest(JSONObject(), request.effectiveConfig)
                    else -> ProviderReasoning.applyOpenAiCompatibleRequest(JSONObject(), request.effectiveConfig)
                }
                response("bounded")
            }, AgentRunController(), false).summarize(history())
            assertEquals(ReasoningEffort.HIGH, configured.effectiveReasoningEffort)
        }
    }

    @Test
    fun summaryCanDisableOptionalThinkingAndUsesDefaultForUnknownOrMandatoryDefaultModels() {
        val optional = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true)
        val defaultOnly = ModelReasoningCapabilities(mandatory = true)
        for ((capabilities, expected) in listOf(optional to ReasoningEffort.OFF,
            defaultOnly to ReasoningEffort.DEFAULT, null to ReasoningEffort.DEFAULT)) {
            AgentContextSummarizer(config.copy(reasoningEffort = ReasoningEffort.HIGH, reasoningCapabilities = capabilities),
                provider { request, _ ->
                    assertEquals(expected, request.config.effectiveReasoningEffort)
                    response("bounded")
                }, AgentRunController(), false).summarize(history())
        }
    }

    @Test
    fun localFallbackIsExplicitBoundedAndDoesNotInvokeProvider() {
        val fallback = AgentContextSummarizer.fallbackSummary(listOf(
            AgentModelClient.ConversationMessage("user", "goal"),
            AgentModelClient.ConversationMessage("assistant", reasoningContent = "PRIVATE_REASONING",
                toolCallsJson = """[{"id":"secret","function":{"name":"read_sms_code","arguments":"PRIVATE_ARGS"}}]"""),
            AgentModelClient.ConversationMessage("tool", "PRIVATE_RESULT", toolCallId = "secret"),
        ))
        assertTrue(fallback.contains("goal"))
        assertFalse(fallback.contains("PRIVATE_"))
        assertTrue(fallback.length <= 12_000)
        val original = jsonHistory()
        val before = original.toString()
        val result = AgentContextCompactor(config, provider { _, _ -> error("fallback must not call the model") }, AgentRunController())
            .fallbackCompact(original, 1, emptySet())
        assertTrue(result.toString().length < before.length)
        assertEquals(before, original.toString())
    }

    private fun history() = (1..6).flatMap { turn -> listOf(
        AgentModelClient.ConversationMessage("user", "问题 $turn"),
        AgentModelClient.ConversationMessage("assistant", "事实 ".repeat(1000)),
    ) }

    private fun jsonHistory() = JSONArray().put(JSONObject().put("role", "system").put("content", "固定规则")).also {
        history().forEach { message -> it.put(AgentConversationCodec.toJsonObject(message)) }
    }

    private fun response(text: String, stop: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", stop),
    )

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            block(request, onEvent)
    }
}
