package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.data.db.EtaDatabase
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AgentRunCheckpointStoreTest {
    @Test
    fun parallelChildProgressKeepsEveryCheckpointEventAndTerminalActivity() {
        val recorder = AgentRunCheckpointRecorder.create(context, request("parallel-progress"))!!
        val workers = Executors.newFixedThreadPool(4)
        try {
            (1..8).map { child -> workers.submit {
                recorder.accept(AgentEvent.ToolStarted(1, "call-$child", "subagent", "explore"))
                recorder.accept(AgentEvent.SubagentProgress(1, "call-$child", "sub_$child", "explore",
                    SubagentActivitySnapshot(subagentId = "sub_$child", currentAction = "正在执行").encode()))
            } }.forEach { it.get(30, TimeUnit.SECONDS) }
            recorder.seal("已中断", cancelled = true)
            val events = AgentRunCheckpointStore.list(context).single().events
            assertEquals(8, events.filterIsInstance<AgentEvent.ToolStarted>().size)
            assertTrue(events.none { it is AgentEvent.SubagentProgress })
            val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
            assertEquals(8, finished.size)
            assertEquals(8, finished.map { it.toolCallId }.toSet().size)
            finished.forEach { event ->
                val snapshot = SubagentActivitySnapshot.decode(event.command)!!
                assertEquals(event.toolCallId.removePrefix("call-"), snapshot.subagentId.removePrefix("sub_"))
                assertEquals(SubagentActivitySnapshot.STATUS_CANCELLED, snapshot.status)
                assertEquals("已中断", snapshot.currentAction)
            }
        } finally { workers.shutdownNow() }
    }

    @Test
    fun cancellationSealsRunningSubagentAndToolActivitiesInCheckpoint() {
        val request = request("cancel-running")
        val recorder = AgentRunCheckpointRecorder.create(context, request)!!
        recorder.accept(AgentEvent.ToolStarted(1, "child", "subagent", "explore"))
        recorder.accept(AgentEvent.ToolStarted(1, "shell", "terminal", "command"))
        val progress = AgentEvent.SubagentProgress(1, "child", "sub_1", "explore",
            SubagentActivitySnapshot(subagentId = "sub_1", currentAction = "正在启动…", output = "已收集的部分证据").encode())
        assertNull(progress.recoveryProjection())
        assertEquals(progress, progress.replayProjection())
        assertNull(AgentEventJsonCodec.decode(AgentEventJsonCodec.encode(progress)))
        recorder.accept(progress)
        // Direct storage also keeps intermediate progress out of the durable journal.
        AgentRunCheckpointStore.append(context, request.runId, 99, progress)
        recorder.seal("已停止", cancelled = true)
        recorder.seal("不能重复收尾", cancelled = true)
        EtaDatabase.closeForTests()
        val events = AgentRunCheckpointStore.list(context).single().events
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>()
        assertEquals(2, finished.size)
        assertTrue(finished.all { it.success == false && it.resultSummary == "已停止" })
        val child = SubagentActivitySnapshot.decode(finished.single { it.name == "subagent" }.command)!!
        assertEquals(SubagentActivitySnapshot.STATUS_CANCELLED, child.status)
        assertEquals("已停止", child.currentAction)
        assertEquals("已收集的部分证据", child.output)
        assertTrue(events.none { it is AgentEvent.SubagentProgress })
    }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After
    fun tearDown() {
        EtaDatabase.closeForTests()
    }

    @Test
    fun searchSuggestionsNeverReachCheckpointEvenThroughDirectAppend() {
        val request = request("search-transient")
        val recorder = AgentRunCheckpointRecorder.create(context, request)!!
        val event = AgentEvent.SearchSuggestions(1, 0, "<div>短期搜索建议</div>")
        recorder.accept(event)
        AgentRunCheckpointStore.append(context, request.runId, 0, event)
        recorder.accept(textDelta("正文与引用"))
        recorder.seal()
        val restored = AgentRunCheckpointStore.list(context).single()
        assertEquals(listOf(textDelta("正文与引用")), restored.events)
    }

    @Test
    fun recorderCoalescesTextAndPreservesVisibleToolTrace() {
        var nowNanos = 0L
        val request = request("run-1")
        val recorder = AgentRunCheckpointRecorder.create(
            context = context,
            request = request,
            nanoTime = { nowNanos },
        )!!

        recorder.accept(textDelta("你"))
        nowNanos = 300_000_000L
        recorder.accept(textDelta("好"))
        recorder.accept(
            AgentEvent.AssistantBlockDelta(
                round = 1,
                kind = AgentEvent.AssistantBlockKind.TOOL_CALL,
                index = 1,
                deltaChars = 18,
                delta = "{\"secret\":\"value\"}",
            )
        )
        recorder.accept(
            AgentEvent.ToolStarted(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                argsPreview = "执行命令 · Android · root",
                command = "uptime",
            )
        )
        recorder.accept(
            AgentEvent.ToolFinished(
                round = 1,
                toolCallId = "call-1",
                name = "run_command",
                resultSummary = "完成",
                imageCount = 0,
                imageBytes = 0,
                success = true,
            )
        )

        val restored = AgentRunCheckpointStore.list(context).single()
        val delta = restored.events.filterIsInstance<AgentEvent.AssistantBlockDelta>().single()
        assertEquals("你好", delta.delta)
        assertEquals(2, delta.deltaChars)
        val toolStarted = restored.events.filterIsInstance<AgentEvent.ToolStarted>().single()
        assertEquals("执行命令 · Android · root", toolStarted.argsPreview)
        assertEquals("uptime", toolStarted.command)
        val toolFinished = restored.events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("完成", toolFinished.resultSummary)
        assertFalse(restored.events.any { event ->
            event is AgentEvent.AssistantBlockDelta &&
                event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL
        })

        recorder.seal()
        assertEquals(
            "run-1",
            AgentRunCheckpointStore.list(context).single().runId,
        )
        AgentRunCheckpointStore.remove(context, "run-1")
        assertTrue(
            AgentRunCheckpointStore.list(context).isEmpty()
        )
    }

    @Test
    fun checkpointsRemainVisibleToAReplacementUiInTheSameProcess() {
        val request = request("run-2")
        assertTrue(
            AgentRunCheckpointStore.start(
                context = context,
                request = request,
                ownerInstanceId = "old-process",
            )
        )
        AgentRunCheckpointStore.append(
            context = context,
            runId = request.runId,
            sortIndex = 0,
            event = AgentEvent.RunStarted(0, 0, 1, false),
        )

        assertEquals(
            "run-2",
            AgentRunCheckpointStore.list(context).single().runId,
        )
    }

    @Test
    fun discardRemovesCheckpointWithoutFlushingBufferedText() {
        val recorder = AgentRunCheckpointRecorder.create(
            context = context,
            request = request("run-discard"),
            nanoTime = { 0L },
        )!!
        recorder.accept(textDelta("不应保留"))

        recorder.discard()

        assertTrue(
            AgentRunCheckpointStore.list(context).isEmpty()
        )
    }

    private fun request(runId: String): AgentRuntimeWire.RunRequest =
        AgentRuntimeWire.RunRequest(
            runId = runId,
            prompt = "测试",
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://example.com/v1",
                apiKey = "test-key",
                model = "test-model",
                systemPrompt = "",
            ),
            images = emptyList(),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = runId,
                source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload = "conversation-1",
            ),
        )

    private fun textDelta(text: String): AgentEvent.AssistantBlockDelta =
        AgentEvent.AssistantBlockDelta(
            round = 1,
            kind = AgentEvent.AssistantBlockKind.TEXT,
            index = 0,
            deltaChars = text.length,
            delta = text,
        )
}
