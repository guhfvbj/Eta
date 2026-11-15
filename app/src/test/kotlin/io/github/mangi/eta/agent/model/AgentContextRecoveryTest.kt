package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextRecoveryTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        contextWindow = 900_000,
    )

    @Test fun identicalCheckpointsAreSkippedButUsageMessagesAndTranscriptChangesAreSaved() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed"))
            .put(AgentConversationCodec.userTextMessage("hello"))
        val saved = mutableListOf<AgentContextSnapshot>()
        var transcriptSize = 0
        val session = AgentContextSession(config.copy(autoCompactionEnabled = false), messages, 1,
            "operation", provider { _, _ -> error("No compaction expected") }, AgentRunController(),
            { emptySet() }, {}, saved::add, transcriptSize = { transcriptSize })
        session.checkpoint()
        session.checkpoint()
        session.compact()
        assertEquals(1, saved.size)
        session.captureRequest()
        session.observeInputTokens(1234)
        session.compact()
        assertEquals(2, saved.size)
        assertEquals(1234, saved.last().lastContextTokens)
        messages.put(AgentConversationCodec.userTextMessage("new turn"))
        session.userAppended()
        session.compact()
        assertEquals(3, saved.size)
        assertEquals(2, saved.last().consumedUserTurns)
        assertEquals(1, saved.last().consumedSupplementCount)
        transcriptSize++
        session.compact()
        session.checkpoint()
        assertEquals(4, saved.size)
        assertEquals(1, saved.last().consumedTranscriptMessages)
        assertEquals(saved.last().encode(), session.snapshot()!!.encode())
    }

    @Test fun failedSnapshotPersistenceIsRetriedBeforeDeduplication() {
        var attempts = 0
        val session = AgentContextSession(config.copy(autoCompactionEnabled = false), messages(), 1,
            "operation", provider { _, _ -> error("No compaction expected") }, AgentRunController(),
            { emptySet() }, {}, { if (++attempts == 1) error("storage unavailable") })
        assertThrows(IllegalStateException::class.java) { session.checkpoint() }
        assertNotNull(session.snapshot())
        session.checkpoint()
        session.compact()
        assertEquals(2, attempts)
    }

    @Test
    fun unknownWindowStillRunsButNeverAutoCompacts() {
        for (window in listOf(null, 0, -1)) {
            var summaries = 0
            val result = AgentModelClient.complete(config.copy(contextWindow = window), "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("摘要")
                    } else {
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10_000_000)))
                        response("完成")
                    }
                })
            assertEquals("完成", result.content)
            assertEquals(0, summaries)
        }
    }

    @Test
    fun automaticCompactionUsesObservedBaselinePlusEstimatedDeltaAtSeventyFivePercent() {
        for (input in listOf(null, 300_000, 674_000, 675_000)) {
            var summaries = 0
            val events = mutableListOf<AgentEvent>()
            val result = AgentModelClient.complete(config, "继续",
                AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), onEvent = events::add, provider = provider { request, emit ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("此前任务已完成。")
                    } else {
                        assertTrue(request.messages.toString().contains("长".repeat(100)))
                        emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = input, outputTokens = 800_000)))
                        response("完成")
                    }
                })
            assertEquals(if (input == 675_000) 1 else 0, summaries)
            assertEquals("完成", result.content)
            val completed = events.filterIsInstance<AgentEvent.ContextCompaction>().lastOrNull()
            if (input == 675_000) {
                assertTrue(completed!!.tokensBefore!! >= 675_000)
                assertNull(completed.tokensAfter)
            } else assertNull(completed)
        }
    }

    @Test
    fun disabledAutomaticCompactionStillAllowsManualCompaction() {
        val disabled = config.copy(autoCompactionEnabled = false)
        var summaries = 0
        val events = mutableListOf<AgentEvent>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                response("此前任务已完成。")
            } else {
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                response("完成")
            }
        }
        val answer = AgentModelClient.complete(disabled, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, onEvent = events::add)
        assertEquals("完成", answer.content)
        assertEquals(0, summaries)
        assertTrue(events.none { it is AgentEvent.ContextCompaction })
        assertNotNull(answer.contextSnapshot!!.lastContextTokens)

        val manual = AgentModelClient.complete(disabled, "", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider, compactOnly = true)
        assertEquals(1, summaries)
        assertNotNull(manual.contextSnapshot)
        assertTrue(manual.transcript.isEmpty())
    }

    @Test
    fun compactionDoesNotRepeatUntilNewUsageArrives() {
        val messages = messages()
        var summaries = 0
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            summaries++
            response("历史已完成。")
        }, AgentRunController(), { emptySet() }, {}, {})
        session.observeInputTokens(675_000)
        session.compact()
        session.compact()
        assertEquals(1, summaries)
        assertNotNull(session.snapshot())
    }

    @Test
    fun summaryFailureDoesNotSplitOrRetryAndKeepsOriginalHistory() {
        for (code in listOf("CONTEXT_OVERFLOW", "MODEL_TIMEOUT")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                throw AgentModelFailure(code, true, "fixture")
            }, AgentRunController(), { emptySet() }, {}, { fail("失败摘要不能提交") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals(code, failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun invalidSummaryDoesNotRetryAndKeepsOriginalHistory() {
        for ((content, stop) in listOf("" to "stop", "半截" to "length", "长".repeat(12_001) to "stop")) {
            val messages = messages()
            val original = messages.toString()
            var requests = 0
            val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
                requests++
                response(content, stop)
            }, AgentRunController(), { emptySet() }, {}, { fail("不应提交无效摘要") })
            val failure = assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
            assertEquals("CONTEXT_SUMMARY_INVALID", failure.code)
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
        }
    }

    @Test
    fun summaryToolCallAndLaterInvalidRollingChunkNeverCommitPartialSummary() {
        for (streamed in listOf(false, true)) {
            val original = messages()
            val before = original.toString()
            val session = AgentContextSession(config, original, 1, "operation", provider { _, emit ->
                if (streamed) emit(ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, 0, name = "read_file"))
                ProviderResponse(JSONObject().put("role", "assistant").put("content", "invalid")
                    .put("finish_reason", "stop").put("tool_calls", JSONArray().put(JSONObject()
                        .put("id", "forbidden").put("function", JSONObject().put("name", "read_file").put("arguments", "{}")))))
            }, AgentRunController(), { emptySet() }, {}, { fail("tool summary must not commit") })
            assertEquals("CONTEXT_SUMMARY_INVALID", assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }.code)
            assertEquals(before, original.toString())
            assertNull(session.snapshot())
        }

        val original = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed"))
        repeat(90) { index ->
            original.put(AgentConversationCodec.userTextMessage("turn $index"))
            original.put(JSONObject().put("role", "assistant").put("content", "code".repeat(2_000)))
        }
        val before = original.toString()
        var calls = 0
        val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
            if (++calls == 1) response("valid first chunk") else response("", "length")
        }, AgentRunController(), { emptySet() }, {}, { fail("partial rolling summary must not commit") })
        assertEquals("CONTEXT_SUMMARY_INVALID", assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }.code)
        assertEquals(2, calls)
        assertEquals(before, original.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun emptyLengthResponseIsNotReplayedEvenWhenInputIsNearWindow() {
        var requests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    response("", "length")
                })
        }
        assertEquals("MODEL_OUTPUT_LIMIT", (failure.cause as AgentModelFailure).code)
        assertEquals(1, requests)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun failedChatAttemptUsageCannotTriggerCompactionAfterRetry() {
        var requests = 0
        val loop = AgentLoop(
            config, messages(), JSONArray(), provider { request, emit ->
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                if (++requests == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 850_000)))
                    throw AgentModelFailure.incompleteStream("fixture")
                }
                response("完成")
            }, AgentModelClient.ToolExecutor { error("不应执行工具") }, AgentRunController(), AgentTraceFormatter(), {},
            modelRetry = AgentModelRetry { _, _ -> }, systemCount = 1,
        )
        assertEquals("完成", loop.run().content)
        assertEquals(2, requests)
        assertTrue(loop.contextSnapshot()!!.lastContextTokens!! < 675_000)
    }

    @Test
    fun summaryCancellationKeepsOriginalContext() {
        val messages = messages()
        val original = messages.toString()
        val controller = AgentRunController()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            controller.cancel()
            response("不应提交")
        }, controller, { emptySet() }, {}, { fail("取消时不能提交") })
        assertThrows(Exception::class.java) { session.compact(force = true) }
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun onlySummaryRequestsHaveAnOverallTimeout() {
        assertEquals(300_000, AgentHttpClient.modelClient(ProviderRequestPurpose.COMPACTION).callTimeoutMillis)
        assertEquals(0, AgentHttpClient.modelClient(ProviderRequestPurpose.CHAT).callTimeoutMillis)
    }

    @Test
    fun normalRunWithoutCompactionPersistsObservedUsageAndCompletedHistory() {
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val events = mutableListOf<AgentEvent>()
        val result = AgentModelClient.complete(config, "hello", { error("no tools") },
            onContextSnapshot = snapshots::add, onEvent = events::add,
            provider = provider { _, emit ->
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 12_345)))
                response("completed")
            })
        val saved = AgentContextSnapshot.decode(result.contextSnapshot!!.encode())!!
        assertEquals(saved, snapshots.last())
        assertEquals("completed", saved.messages.last().content)
        assertEquals(result.transcript.size, saved.consumedTranscriptMessages)
        assertTrue(saved.lastContextTokens!! in 12_346..12_500)
        assertTrue(events.none { it is AgentEvent.ContextCompaction })
    }

    @Test
    fun stopDuringProviderRequestCheckpointsEstimateWithoutFailedAttemptUsage() {
        val controller = AgentRunController()
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "hello", { error("no tools") },
                runController = controller, onContextSnapshot = snapshots::add,
                provider = provider { _, emit ->
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 99_000_000)))
                    controller.cancel()
                    controller.throwIfCancelled()
                    error("cancelled")
                })
        }
        assertTrue(controller.isCancelled)
        assertEquals(failure.contextSnapshot, snapshots.last())
        assertEquals(listOf("user"), snapshots.last().messages.map { it.role })
        assertTrue(snapshots.last().lastContextTokens!! in 1..100_000)
    }

    @Test
    fun emergencyCompactionDoesNotTurnCancellationIntoLocalFallback() {
        for (cancel in listOf(AgentRunCancelledException(), CancellationException("cancelled"))) {
            val source = messages()
            val before = source.toString()
            var committed = false
            val session = AgentContextSession(config, source, 1, "cancelled-summary", provider { _, _ ->
                throw cancel
            }, AgentRunController(), { emptySet() }, {}, { committed = true })
            assertThrows(cancel.javaClass) { session.compact(force = true, emergency = true) }
            assertFalse(committed)
            assertEquals(before, source.toString())
        }
    }

    @Test
    fun failedEmergencyFallbackCommitKeepsLiveContextAndObservedBaseline() {
        val source = messages()
        val before = source.toString()
        val session = AgentContextSession(config, source, 1, "emergency", provider { _, _ ->
            response("truncated summary", "length")
        }, AgentRunController(), { emptySet() }, {}, { error("storage unavailable") })
        session.observeInputTokens(700_000)
        assertThrows(IllegalStateException::class.java) { session.compact(force = true, emergency = true) }
        assertEquals(before, source.toString())
        assertEquals(700_000, session.effectiveTokens())
        assertNull(session.snapshot())
    }

    @Test
    fun failedRunPersistsEstimateAndRetryCompactsBeforeRequest() {
        val large = history().map { if (it.role == "assistant") it.copy(content = "代码".repeat(50_000)) else it }
        val bounded = config.copy(contextWindow = 128_000)
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(bounded.copy(autoCompactionEnabled = false), "继续", { error("no tools") },
                history = large, provider = provider { _, emit ->
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 99_000_000)))
                    throw AgentModelFailure("HTTP_400", false, "fixture")
                })
        }
        val saved = AgentContextSnapshot.decode(failure.contextSnapshot!!.encode())!!
        assertTrue(saved.lastContextTokens!! in 100_000..1_000_000) // failed usage must not leak
        var summaries = 0
        val resumed = AgentModelClient.complete(bounded, "重试", { error("no tools") }, history = saved.messages,
            initialTokenBaseline = saved.tokenBaseline, provider = provider { request, _ ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    response("已收集历史证据")
                } else {
                    assertTrue(summaries > 0)
                    assertFalse(request.messages.toString().contains("代码".repeat(100)))
                    response("完成")
                }
            })
        assertEquals("完成", resumed.content)
    }

    @Test
    fun stopAndNewMessageInterruptionKeepClosedSnapshotAndResumeCompacts() {
        for (reason in listOf("stopped", "interrupted")) {
            val controller = AgentRunController()
            val bounded = config.copy(contextWindow = 128_000, terminalTools = true)
            val failure = assertThrows(AgentModelExecutionException::class.java) {
                AgentModelClient.complete(bounded.copy(autoCompactionEnabled = false), reason,
                    { controller.cancel(); controller.throwIfCancelled(); error("cancelled") },
                    history = history(), runController = controller, provider = provider { _, _ ->
                        ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                            .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                                .put("id", "pending").put("type", "function").put("function", JSONObject()
                                    .put("name", "read_file").put("arguments", "{}")))))
                    })
            }
            val snapshot = AgentContextSnapshot.decode(failure.contextSnapshot!!.encode())!!
            assertTrue(snapshot.lastContextTokens!! >= bounded.contextWindow!! * 0.75)
            assertEquals("pending", snapshot.messages.last().toolCallId)
            assertTrue(snapshot.messages.last().content.contains("TOOL_INTERRUPTED"))
            assertEquals(failure.transcript.size, snapshot.consumedTranscriptMessages)
            assertEquals(snapshot.messages, AgentToolBatchRecovery.completeInterrupted(snapshot.messages))
            var summaries = 0
            AgentModelClient.complete(bounded, "继续", { error("must not replay interrupted tool") },
                history = snapshot.messages, initialTokenBaseline = snapshot.tokenBaseline,
                provider = provider { request, _ ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("历史任务中断，请先核实工具状态")
                    } else {
                        assertTrue("$reason must compact before continuation", summaries > 0)
                        response("继续完成")
                    }
                })
        }
    }

    @Test
    fun switchingToSmallerWindowInvalidatesObservedBaselineAndCompactsFirst() {
        val original = AgentModelClient.complete(config, "原模型", { error("no tools") }, history = history(),
            provider = provider { _, emit ->
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 10))) // intentionally low old-model count
                response("原模型已完成")
            })
        val checkpoint = original.contextSnapshot!!
        var summaries = 0
        AgentModelClient.complete(config.copy(model = "smaller", contextWindow = 128_000), "继续",
            { error("no tools") }, history = checkpoint.messages, initialTokenBaseline = checkpoint.tokenBaseline,
            provider = provider { request, _ ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    response("保留旧事实")
                } else {
                    assertTrue(summaries > 0)
                    response("完成")
                }
            })
    }

    @Test
    fun highContextEmptyResponseFallsBackLocallyIfEmergencySummarizationFails() {
        var chats = 0
        var summaries = 0
        val result = AgentModelClient.complete(config.copy(contextWindow = 256_000, autoCompactionEnabled = false),
            "继续", { error("no tools") }, history = history(200_000), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaries++
                    response("无效摘要", "length")
                } else if (++chats == 1) {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 99_000_000)))
                    response("")
                } else {
                    assertTrue(request.messages.toString().contains("本地应急整理"))
                    response("恢复完成")
                }
            })
        assertEquals(2, chats)
        assertEquals(1, summaries)
        assertEquals("恢复完成", result.content)
        assertTrue(result.contextSnapshot!!.lastContextTokens!! < 99_000_000)
    }

    @Test
    fun lowContextEmptyResponseAndOutputLimitNeverStartEmergencyCompaction() {
        for (stop in listOf("stop", "length")) {
            var requests = 0
            val failure = assertThrows(AgentModelExecutionException::class.java) {
                AgentModelClient.complete(config, "hello", { error("no tools") }, provider = provider { request, _ ->
                    assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                    requests++
                    response("", stop)
                })
            }
            assertEquals(1, requests)
            assertEquals(if (stop == "length") "MODEL_OUTPUT_LIMIT" else "MODEL_EMPTY_RESPONSE",
                (failure.cause as AgentModelFailure).code)
        }
    }

    @Test
    fun repeatedOverflowHasBoundedRecoveryBudget() {
        var chats = 0
        var summaries = 0
        val events = mutableListOf<AgentEvent>()
        assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config.copy(autoCompactionEnabled = false), "继续", { error("no tools") },
                history = (1..8).flatMap { listOf(AgentModelClient.ConversationMessage("user", "turn $it"),
                    AgentModelClient.ConversationMessage("assistant", "fact $it ".repeat(3_000))) },
                onEvent = events::add, provider = provider { request, _ ->
                    if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                        summaries++
                        response("有界摘要 $summaries")
                    } else {
                        chats++
                        throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                    }
                })
        }
        assertTrue(chats in 2..3)
        assertTrue(summaries > 0)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().count { it.phase == "started" } in 1..2)
        assertTrue(events.filterIsInstance<AgentEvent.ModelRetryScheduled>().size <= 2)
    }

    @Test
    fun pendingSignedToolChainsNeverCompactOrReplayAfterOverflow() {
        for (kind in listOf("anthropic", "responses", "gemini")) {
            val messages = messages()
            val call = JSONObject().put("role", "assistant").put("content", "")
                .put("tool_calls", JSONArray().put(JSONObject().put("id", "signed")
                    .put("function", JSONObject().put("name", "device_info").put("arguments", "{}"))))
            when (kind) {
                "anthropic" -> AnthropicEphemeralState.attachContentBlocks(call, JSONArray()
                    .put(JSONObject().put("type", "thinking").put("thinking", "private").put("signature", "signature"))
                    .put(JSONObject().put("type", "tool_use").put("id", "signed").put("name", "device_info").put("input", JSONObject())))
                "responses" -> ResponsesEphemeralState.attachOutputItems(call, JSONArray()
                    .put(JSONObject().put("type", "function_call").put("call_id", "signed")))
                else -> GeminiEphemeralState.attachSteps(call, JSONArray().put(JSONObject().put("type", "function_call")))
            }
            messages.put(call).put(JSONObject().put("role", "tool").put("tool_call_id", "signed").put("content", "done"))
            var requests = 0
            val original = messages.toString()
            val loop = AgentLoop(config, messages, JSONArray(), provider { request, _ ->
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                requests++
                throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
            }, { error("no tools") }, AgentRunController(), AgentTraceFormatter(), {}, systemCount = 1)
            assertThrows(AgentModelFailure::class.java) { loop.run() }
            assertEquals(1, requests)
            assertEquals(original, messages.toString())
        }
    }

    private fun history(assistantChars: Int = 100_000) = listOf(
        AgentModelClient.ConversationMessage("user", "旧任务"),
        AgentModelClient.ConversationMessage("assistant", "长".repeat(assistantChars)),
    )

    private fun messages() = JSONArray().put(JSONObject().put("role", "system").put("content", "固定约束")).also { source ->
        history().forEach { source.put(AgentConversationCodec.toJsonObject(it)) }
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
