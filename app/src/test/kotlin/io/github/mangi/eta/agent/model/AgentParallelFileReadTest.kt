package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunToolActivityState
import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ExecutionException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentParallelFileReadTest {
    @Test fun allFiveReadToolsRunAtMostFourWideWithOrderedResultsAndSerializedUiEvents() {
        val names = listOf("read_file", "stat_file", "list_directory", "glob_files", "grep_files")
        val calls = List(10) { call("read-$it", names[it % names.size]) }
        val firstWave = CountDownLatch(4)
        val releaseFirst = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val owner = Thread.currentThread()
        val state = AgentRunToolActivityState()
        val projector = AgentRunMessageProjector { 123L }
        var ui = emptyList<AgentChatMessageUi>()
        val harness = Harness(calls, execute = { call ->
            assertNotSame(owner, Thread.currentThread())
            val count = active.incrementAndGet()
            peak.accumulateAndGet(count, ::maxOf)
            try {
                if (call.id.removePrefix("read-").toInt() < 4) {
                    firstWave.countDown()
                    assertTrue(firstWave.await(5, TimeUnit.SECONDS))
                }
                if (call.id == "read-0") assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                AgentModelClient.ToolResult("result-${call.id}", sensitive = call.id == "read-1")
            } finally { active.decrementAndGet() }
        }, event = { event ->
            assertSame(owner, Thread.currentThread())
            state.accept(event)
            ui = projector.applyEvent("parallel", event, ui)
            if (event is AgentEvent.ToolFinished && event.toolCallId != "read-0") releaseFirst.countDown()
        }, verify = { request ->
            val results = (0 until request.messages.length()).map(request.messages::getJSONObject).filter { it.optString("role") == "tool" }
            assertEquals(calls.map { it.id }, results.map { it.getString("tool_call_id") })
            assertEquals(calls.map { "result-${it.id}" }, results.map { it.getString("content") })
        })
        val result = harness.loop.run()
        assertEquals(4, peak.get())
        assertEquals(0, active.get())
        assertEquals(setOf("read-1"), result.sensitiveToolCallIds)
        assertNotEquals("read-0", harness.events.filterIsInstance<AgentEvent.ToolFinished>().first().toolCallId)
        assertEquals(10, harness.events.filterIsInstance<AgentEvent.ToolStarted>().size)
        assertEquals(10, harness.events.filterIsInstance<AgentEvent.ToolFinished>().size)
        assertTrue(state.finish("done", false).isEmpty())
        val activities = ui.filterIsInstance<ToolActivityMessageUi>()
        assertEquals(10, activities.size)
        assertTrue(activities.none { it.status == ToolActivityStatusUi.Running })
        val saved = AgentConversationCodec.transcript(harness.transcript, 0, result.sensitiveToolCallIds)
        assertFalse(saved.first { it.toolCallId == "read-1" }.content.contains("result-read-1"))
    }

    @Test fun writesShellGuiMemoryAndMcpCallsAreSequentialBarriersBetweenReadBatches() {
        val names = listOf("read_file", "stat_file", "write_file", "glob_files", "grep_files", "terminal",
            "list_directory", "read_file", "tap", "memory_get", "mcp_server_read")
        val calls = names.mapIndexed { i, name -> call("call-$i", name) }
        val active = AtomicInteger()
        val completed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val harness = Harness(calls, execute = { call ->
            if (call.name in setOf("write_file", "terminal", "tap", "memory_get", "mcp_server_read")) {
                assertEquals(0, active.get())
                val index = call.id.removePrefix("call-").toInt()
                assertTrue(calls.take(index).all { it.id in completed })
            }
            active.incrementAndGet()
            try { Thread.sleep(5); AgentModelClient.ToolResult("ok") }
            finally { active.decrementAndGet(); completed += call.id }
        })
        harness.loop.run()
        assertEquals(calls.map { it.id }.toSet(), completed)
        assertEquals(calls.map { it.id }, (0 until harness.messages.length()).map(harness.messages::getJSONObject)
            .filter { it.optString("role") == "tool" }.map { it.getString("tool_call_id") })
    }

    @Test fun unavailableReadIsRejectedAndDoesNotJoinAnAllowedBatch() {
        val executed = mutableListOf<String>()
        val harness = Harness(listOf(call("one", "read_file"), call("denied", "grep_files"), call("two", "read_file")),
            available = listOf("read_file"), execute = { executed += it.id; AgentModelClient.ToolResult("ok") }, verify = { request ->
                val denied = (0 until request.messages.length()).map(request.messages::getJSONObject)
                    .single { it.optString("tool_call_id") == "denied" }
                assertEquals("TOOL_NOT_AVAILABLE", JSONObject(denied.getString("content")).getString("code"))
            })
        harness.loop.run()
        assertEquals(listOf("one", "two"), executed)
    }

    @Test fun oneReadFailureDoesNotCancelSiblingsOrBreakCallResultPairing() {
        val harness = Harness(List(4) { call("read-$it", "read_file") }, execute = {
            if (it.id == "read-1") error("file failure")
            AgentModelClient.ToolResult("ok-${it.id}")
        }, verify = { request ->
            val results = (0 until request.messages.length()).map(request.messages::getJSONObject).filter { it.optString("role") == "tool" }
            assertEquals(listOf("read-0", "read-1", "read-2", "read-3"), results.map { it.getString("tool_call_id") })
            assertEquals("TOOL_ERROR", JSONObject(results[1].getString("content")).getString("code"))
            assertEquals("ok-read-3", results[3].getString("content"))
        })
        assertEquals("done", harness.loop.run().content)
        assertEquals(4, harness.events.filterIsInstance<AgentEvent.ToolFinished>().size)
    }

    @Test fun signedParallelToolBatchesKeepProviderPayloadsAndCatalogUnchanged() {
        for (signedProvider in 0..2) {
            var original = ""
            val harness = Harness(List(2) { call("signed-$it", "read_file") }, execute = { AgentModelClient.ToolResult("exact-${it.id}") },
                decorate = { message ->
                    when (signedProvider) {
                        0 -> AnthropicEphemeralState.attachContentBlocks(message, JSONArray().put(JSONObject().put("type", "thinking").put("signature", "opaque")))
                        1 -> GeminiEphemeralState.attachSteps(message, JSONArray().put(JSONObject().put("type", "function_call").put("thought_signature", "opaque")))
                        2 -> ResponsesEphemeralState.attachOutputItems(message, JSONArray().put(JSONObject().put("type", "function_call").put("call_id", "signed-0")))
                    }
                    original = when (signedProvider) {
                        0 -> AnthropicEphemeralState.contentBlocks(message)
                        1 -> GeminiEphemeralState.steps(message)
                        else -> ResponsesEphemeralState.outputItems(message)
                    }.toString()
                }, event = {}, verify = { request ->
                    assertTrue(SignedToolContext.hasPendingResponse(request.messages))
                    val assistant = (0 until request.messages.length()).map(request.messages::getJSONObject).single { it.optString("role") == "assistant" }
                    val actual = when (signedProvider) {
                        0 -> AnthropicEphemeralState.contentBlocks(assistant)
                        1 -> GeminiEphemeralState.steps(assistant)
                        else -> ResponsesEphemeralState.outputItems(assistant)
                    }.toString()
                    assertEquals(original, actual)
                    assertEquals(listOf("signed-0", "signed-1"), AgentConversationCodec.parseToolCalls(assistant).map { it.id })
                    assertTrue(original.contains("opaque") || original.contains("signed-0"))
                    assertEquals(1, request.tools.length())
                })
            harness.loop.run()
            assertTrue(original.isNotEmpty())
            assertEquals(1, harness.toolRefreshes)
        }
    }

    @Test fun cancellationInterruptsAllWorkersAndLeavesRecoverableToolActivitiesAndHistory() {
        val entered = CountDownLatch(4)
        val exited = CountDownLatch(4)
        val blocked = CountDownLatch(1)
        val harness = Harness(List(4) { call("read-$it", "read_file") }, execute = {
            entered.countDown()
            try { blocked.await(); AgentModelClient.ToolResult("unreachable") }
            finally { exited.countDown() }
        })
        val owner = Executors.newSingleThreadExecutor()
        try {
            val result = owner.submit<AgentLoop.Result> { harness.loop.run() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            harness.controller.cancel()
            val error = assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
            assertTrue(error.cause.toString(), error.cause is AgentRunCancelledException)
            assertTrue(exited.await(5, TimeUnit.SECONDS))
            assertTrue(harness.events.filterIsInstance<AgentEvent.ToolFinished>().isEmpty())
            val activities = AgentRunToolActivityState()
            harness.events.forEach(activities::accept)
            assertEquals(4, activities.finish("cancelled", true).size)
            harness.loop.checkpointContext()
            val saved = checkNotNull(harness.loop.contextSnapshot())
            val tools = saved.messages.filter { it.role == "tool" }
            assertEquals(listOf("read-0", "read-1", "read-2", "read-3"), tools.map { it.toolCallId })
            assertTrue(tools.all { it.content.contains("TOOL_INTERRUPTED") })
        } finally { blocked.countDown(); owner.shutdownNow() }
    }

    @Test fun cancellationKeepsAlreadyPublishedOutOfOrderResultsInJournalAndContext() {
        val entered = CountDownLatch(2)
        val blocked = CountDownLatch(1)
        val controller = AgentRunController()
        val harness = Harness(listOf(call("slow", "read_file"), call("completed", "stat_file")),
            controller = controller, execute = { call ->
                entered.countDown()
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                if (call.id == "slow") blocked.await()
                AgentModelClient.ToolResult("actual-${call.id}")
            }, event = { event ->
                if (event is AgentEvent.ToolFinished && event.toolCallId == "completed") controller.cancel()
            })
        try {
            assertThrows(AgentRunCancelledException::class.java) { harness.loop.run() }
            val journal = AgentConversationCodec.transcript(harness.transcript, 0)
            assertEquals("actual-completed", journal.single { it.toolCallId == "completed" }.content)
            harness.loop.checkpointContext()
            val saved = checkNotNull(harness.loop.contextSnapshot()).messages
            assertEquals("actual-completed", saved.single { it.toolCallId == "completed" }.content)
            assertTrue(saved.single { it.toolCallId == "slow" }.content.contains("TOOL_INTERRUPTED"))
            assertEquals(1, saved.count { it.toolCallId == "completed" })
        } finally { controller.cancel(); blocked.countDown() }
    }

    @Test fun pauseBeforeWorkerExecutionDefersAllReadsUntilResume() {
        val paused = CountDownLatch(1)
        val started = AtomicInteger()
        val executed = AtomicInteger()
        val controller = AgentRunController()
        val harness = Harness(List(4) { call("read-$it", "read_file") }, controller = controller,
            execute = { executed.incrementAndGet(); AgentModelClient.ToolResult("ok") }, event = { event ->
                if (event is AgentEvent.ToolStarted && started.incrementAndGet() == 4) {
                    controller.pause()
                    paused.countDown()
                }
            })
        val owner = Executors.newSingleThreadExecutor()
        try {
            val result = owner.submit<AgentLoop.Result> { harness.loop.run() }
            assertTrue(paused.await(5, TimeUnit.SECONDS))
            assertEquals(0, executed.get())
            controller.resume()
            assertEquals("done", result.get(5, TimeUnit.SECONDS).content)
            assertEquals(4, executed.get())
        } finally { controller.cancel(); owner.shutdownNow() }
    }

    @Test fun aWorkerCancellationIsNeverTurnedIntoAToolError() {
        val harness = Harness(List(2) { call("read-$it", "read_file") }, execute = { throw CancellationException("stop this batch") })
        assertThrows(CancellationException::class.java) { harness.loop.run() }
        assertTrue(harness.events.filterIsInstance<AgentEvent.ToolFinished>().isEmpty())
    }

    @Test fun toolImagesAreAttachedOnlyAfterTheWholeParallelBatchAndConsumedOnce() {
        val harness = Harness(List(2) { call("read-$it", "read_file") }, execute = {
            AgentModelClient.ToolResult("ok", images = listOf(AgentModelClient.ModelImage("data:image/png;base64,AA==", "image/png", 1)))
        }, verify = { request ->
            val roles = (0 until request.messages.length()).map { request.messages.getJSONObject(it).getString("role") }
            assertEquals(listOf("system", "user", "assistant", "tool", "tool", "user"), roles)
            assertTrue(request.messages.toString().contains("data:image/png"))
        })
        harness.loop.run()
        assertFalse(harness.messages.toString().contains("data:image/png"))
        assertFalse(harness.transcript.toString().contains("data:image/png"))
        assertEquals(2, harness.events.filterIsInstance<AgentEvent.ToolImagesAttached>().size)
    }

    private class Harness(
        calls: List<AgentModelClient.ToolCall>,
        available: List<String> = calls.map { it.name }.distinct(),
        val controller: AgentRunController = AgentRunController(),
        execute: (AgentModelClient.ToolCall) -> AgentModelClient.ToolResult,
        event: (AgentEvent) -> Unit = {},
        verify: (ProviderRequest) -> Unit = {},
        decorate: (JSONObject) -> Unit = {},
    ) {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "fixed"))
            .put(AgentConversationCodec.userTextMessage("inspect"))
        val transcript = JSONArray()
        val events = mutableListOf<AgentEvent>()
        var toolRefreshes = 0
        private val tools = JSONArray(available.map { name -> JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", name).put("parameters", JSONObject().put("type", "object"))) })
        private var round = 0
        private val provider = object : AgentProviderClient {
            override val id = "parallel-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, true)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (round++ == 0) {
                    val message = AgentConversationCodec.assistantHistoryMessage(JSONObject().put("content", ""), calls)
                        .put("finish_reason", "tool_calls")
                    decorate(message)
                    return ProviderResponse(message)
                }
                assertEquals(2, round)
                verify(request)
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop"))
            }
        }
        val loop = AgentLoop(
            config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "key", model = "fixture", systemPrompt = "", autoCompactionEnabled = false),
            messages = messages, tools = tools, provider = provider, toolExecutor = AgentModelClient.ToolExecutor(execute),
            runController = controller, traceFormatter = AgentTraceFormatter(), onEvent = { events += it; event(it) },
            transcript = transcript, systemCount = 1, toolsForRound = { toolRefreshes++; tools },
        )
    }

    private fun call(id: String, name: String) = AgentModelClient.ToolCall(id, name, "{}")
}
