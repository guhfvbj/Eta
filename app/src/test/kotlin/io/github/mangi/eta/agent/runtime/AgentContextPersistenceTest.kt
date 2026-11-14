package io.github.mangi.eta.agent.runtime

import android.app.Application
import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentContextTokenBaseline
import io.github.mangi.eta.agent.subagent.SubagentSessionStore
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.data.db.ConversationEntity
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import io.github.mangi.eta.data.db.EtaDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// 数据库由用例管理，避免 EtaApp 的后台预热与关闭、重开数据库竞争。
@Config(sdk = [36], application = Application::class)
class AgentContextPersistenceTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After fun close() {
        AgentRunArchiveStore.remove(context, "context-persist")
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @Test
    fun committedSnapshotSurvivesDatabaseReopenAndOutboxAcknowledgement() {
        val request = AgentRuntimeWire.RunRequest(
            "context-persist", "", AgentModelClient.ModelConfig(
                baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
            emptyList(), handoff = AgentRuntimeWire.EntryHandoff("context-persist", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "conversation"),
            operation = AgentRuntimeWire.OP_COMPACT,
        )
        val summary = AgentModelClient.ConversationMessage("assistant", "已完成前序操作。",
            contextSummary = true, compactedUserTurns = 2, summaryThroughUserTurn = 2)
        val snapshot = AgentContextSnapshot(operationId = request.runId, messages = listOf(summary),
            coveredUserTurns = 2, consumedUserTurns = 2,
            tokenBaseline = AgentContextTokenBaseline(123_456, 1, "fingerprint", 300, "model"))
        assertTrue(AgentRunCheckpointStore.start(context, request))
        AgentRunCheckpointStore.saveContext(context, request.runId, snapshot)
        val event = AgentEvent.ContextCompaction("op", "completed", 30_000, 2_000)
        AgentRunCheckpointStore.append(context, request.runId, 0, event)
        EtaDatabase.closeForTests()
        val checkpoint = AgentRunCheckpointStore.list(context).single()
        assertEquals(snapshot, checkpoint.contextSnapshot)
        assertEquals(AgentRuntimeWire.OP_COMPACT, checkpoint.operation)
        assertEquals(listOf(event), checkpoint.events)
        val result = AgentRuntimeWire.RunResult(request.runId, true, "", contextSnapshot = snapshot,
            operation = AgentRuntimeWire.OP_COMPACT)
        val completed = AgentRuntimeWire.CompletedRun(request.handoff!!, result, System.currentTimeMillis())
        assertTrue(AgentRuntimeResultStore.add(context, completed))
        AgentRunArchiveStore.add(context, AgentRunArchiveStore.ArchivedRun(completed.handoff, listOf(event), result, completed.createdAt))
        EtaDatabase.closeForTests()
        assertEquals(result, AgentRuntimeResultStore.list(context).single().result)
        assertEquals(result, AgentRunArchiveStore.list(context).single().result)
        AgentRuntimeResultStore.remove(context, request.runId)
        assertTrue(AgentRuntimeResultStore.list(context).isEmpty())
        assertTrue(AgentRunCheckpointStore.list(context).isEmpty())
        assertFalse(AgentRuntimeResultStore.add(context, completed))
    }

    @Test
    fun concurrentParentAndChildMetadataUpdatesPreserveBothFields() = runBlocking {
        EtaDatabase.get(context).conversationDao().insertConversations(listOf(
            ConversationEntity("conversation", "test", false, createdAt = 1, updatedAt = 1)))
        val baseline = AgentContextTokenBaseline(123_456, 1, "fingerprint", 300, "model")
        val children = listOf(SubagentSessionStore.StoredSession("sub_1", SubagentProfile.EXPLORE,
            listOf(AgentModelClient.ConversationMessage("user", "partial task"))))
        val workers = Executors.newFixedThreadPool(2)
        try {
            val parent = workers.submit {
                repeat(8) { AgentConversationRuntimeStore.update(context, "conversation") { it.copy(tokenBaseline = baseline) } }
            }
            val child = workers.submit {
                repeat(8) { AgentConversationRuntimeStore.update(context, "conversation") { it.copy(subagents = children) } }
            }
            parent.get(30, TimeUnit.SECONDS)
            child.get(30, TimeUnit.SECONDS)
        } finally { workers.shutdownNow() }
        EtaDatabase.closeForTests()
        val saved = AgentConversationRuntimeStore.load(context, "conversation")
        assertEquals(baseline, saved.tokenBaseline)
        assertEquals(children, saved.subagents)
    }

    @Test
    fun conversationMetadataAndPartialChildHistorySurviveReopenAndUiHistoryWrites() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        dao.insertConversations(listOf(ConversationEntity("conversation", "test", false, createdAt = 1, updatedAt = 1)))
        val entry = SubagentSessionStore.create("persisted-child", SubagentProfile.CODER)
        entry.history = listOf(AgentModelClient.ConversationMessage("user", "task".repeat(10_000)),
            AgentModelClient.ConversationMessage("assistant", toolCallsJson = """[{"id":"open","function":{"name":"read_file","arguments":"{}"}}]"""))
        entry.openedMcpGroups.add("mcp:files")
        val baseline = AgentContextTokenBaseline(210_000, 2, "fingerprint", 200, "model")
        val state = AgentConversationRuntimeState(tokenBaseline = baseline,
            subagents = SubagentSessionStore.export("persisted-child"))
        AgentConversationRuntimeStore.update(context, "conversation") { state }
        dao.updateContextCheckpoint("conversation", "[]", "[]") // UI save must preserve independently committed metadata
        EtaDatabase.closeForTests()
        SubagentSessionStore.clearForTests()
        val restored = AgentConversationRuntimeStore.load(context, "conversation")
        assertEquals(state, restored)
        SubagentSessionStore.restore("persisted-child", restored.subagents)
        val child = SubagentSessionStore.get("persisted-child", entry.subagentId)!!
        assertFalse(child.running.get())
        assertEquals(setOf("mcp:files"), child.openedMcpGroups)
        assertTrue(child.history.last().content.contains("TOOL_INTERRUPTED"))
        assertEquals("sub_2", SubagentSessionStore.create("persisted-child", SubagentProfile.EXPLORE).subagentId)
    }

    @Test
    fun oldSnapshotsAndEmptyRuntimeMetadataRemainReadable() {
        val old = AgentContextSnapshot.decode("""{"operationId":"legacy","messages":[]}""")!!
        assertNull(old.lastContextTokens)
        assertEquals(AgentConversationRuntimeState(), AgentConversationRuntimeState.decode(""))
    }
}
