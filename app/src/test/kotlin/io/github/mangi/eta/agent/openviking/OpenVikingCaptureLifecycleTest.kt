package io.github.mangi.eta.agent.openviking

import android.app.Application
import androidx.room.withTransaction
import io.github.mangi.eta.agent.memory.ConversationMemoryPins
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.data.model.openVikingHash
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpenVikingCaptureLifecycleTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Test fun successfulRuntimeCompletionQueuesInitialUserAndCleanAssistantThenBatchesCommitsAndPolls() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(app).use { f ->
            val ledger = OpenVikingCaptureRepository(f.database, f.cipher, f.clock::get)
            val initial = OpenVikingCaptureSanitizer.clean("I prefer Kotlin\n<memory_core>injected</memory_core>")!!
            ledger.stage(f.connection, "conversation", "run", listOf(OpenVikingCaptureRepository.Input("user-run", initial)))
            assertTrue(f.database.openVikingDao().sessions(f.connection).isEmpty())
            val provider = object : AgentProviderClient {
                override val id = "fixture"
                override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
                override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) =
                    ProviderResponse(JSONObject().put("role", "assistant").put("content", "Kotlin preference recorded.").put("finish_reason", "stop"))
            }
            val response = AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed"),
                "I prefer Kotlin", AgentModelClient.ToolExecutor { error("No tools") }, provider = provider)
            // Initial user is absent from transcript: the independent ledger is authoritative.
            assertFalse(response.transcript.any { it.role == "user" })
            assertEquals(2, ledger.finish(f.connection, "conversation", "run", 0, "eta", OpenVikingCaptureSanitizer.clean(response.content, assistant = true)))
            val api = FakeOpenVikingApi()
            assertNotNull(OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
            f.coordinator.sync(f.settings, api, force = true)
            assertEquals(listOf("user", "assistant"), api.uploaded.map { it.role })
            assertFalse(api.uploaded.any { it.content.contains("injected") })
            assertEquals(1, api.commits.get())
            f.clock.addAndGet(11_000)
            f.coordinator.sync(f.settings, api, pollOnly = true)
            assertEquals(1, api.polls.get())
            assertNull(OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
        }
    }
    @Test fun replayAfterRestartOrEpochChangeDoesNotDuplicateSourcesAndAbortedDraftIsNeverSent() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(app).use { f ->
            val first = OpenVikingCaptureRepository(f.database, f.cipher, f.clock::get)
            val inputs = listOf(OpenVikingCaptureRepository.Input("stable-message", "I prefer Kotlin"))
            first.stage(f.connection, "conversation", "run1", inputs)
            first.abort(f.connection, "conversation", "run1")
            assertEquals(0, first.finish(f.connection, "conversation", "run1", 0, "eta", null))
            first.stage(f.connection, "conversation", "run2", inputs)
            assertEquals(1, first.finish(f.connection, "conversation", "run2", 0, "eta", null))
            val restarted = OpenVikingCaptureRepository(f.database, f.cipher, f.clock::get)
            restarted.stage(f.connection, "conversation", "run3", inputs)
            assertEquals(0, restarted.finish(f.connection, "conversation", "run3", 1, "eta", null))
            val session = f.database.openVikingDao().scopedSession(f.connection, openVikingHash("conversation"), 0)!!
            assertEquals(1, f.database.openVikingDao().pending(session.id).size)
        }
    }
    @Test fun consumedSteeringUsesOriginalUserTextAndKeepsBackgroundOriginSeparate() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val seen = mutableListOf<Triple<String, String, String>>()
        val provider = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                when (calls.getAndIncrement()) {
                    0 -> assertTrue(runController.steer("I prefer Kotlin", AgentRuntimeWire.SUPPLEMENT_SOURCE_USER))
                    1 -> assertTrue(runController.steer("background notification", AgentRuntimeWire.SUPPLEMENT_SOURCE_BACKGROUND_JOB))
                }
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "A complete answer.").put("finish_reason", "stop"))
            }
        }
        AgentModelClient.complete(AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed"),
            "Original user turn", AgentModelClient.ToolExecutor { error("No tools") }, provider = provider,
            operationId = "run", requestContext = object : AgentRequestContext {
                override fun prepare(messages: org.json.JSONArray) = Unit
                override fun userInput(messageId: String, text: String, source: String) { seen += Triple(messageId, text, source) }
            })
        assertEquals(listOf("I prefer Kotlin", "background notification"), seen.map { it.second })
        assertEquals(listOf(AgentRuntimeWire.SUPPLEMENT_SOURCE_USER, AgentRuntimeWire.SUPPLEMENT_SOURCE_BACKGROUND_JOB), seen.map { it.third })
        assertEquals(listOf("user-run-supplement-1", "user-run-supplement-2"), seen.map { it.first })
    }
    @Test fun compactionAdvancesEpochAndPriorityFlushOnlyUsesPreviouslyCompletedCaptures() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(app).use { f ->
            val ledger = OpenVikingCaptureRepository(f.database, f.cipher, f.clock::get)
            ledger.stage(f.connection, "conversation", "completed", listOf(OpenVikingCaptureRepository.Input("user-completed", "A durable preference")))
            ledger.finish(f.connection, "conversation", "completed", 0, "eta", null)
            ledger.stage(f.connection, "conversation", "in-flight", listOf(OpenVikingCaptureRepository.Input("user-flight", "Never send aborted text")))
            val cache = OpenVikingContextCache(f.database, f.cipher)
            val pin = ConversationMemoryPins.contextFor("conversation", AgentMemorySnapshot("# 核心记忆\nold", "r1", 24, 2), null)
            val api = FakeOpenVikingApi()
            val lifecycle = AgentRuntimeMemoryLifecycle("conversation") { id -> runBlocking { cache.advance(f.connection, "conversation", id); f.coordinator.sync(f.settings, api, force = true) } }
            lifecycle.accept(AgentEvent.ContextCompaction("compact", "completed"))
            assertEquals(1, cache.epoch(f.connection, "conversation"))
            assertEquals(listOf("A durable preference"), api.uploaded.map { it.content })
            assertEquals(1, api.commits.get())
            assertNotSame(pin, ConversationMemoryPins.contextFor("conversation", AgentMemorySnapshot("# 核心记忆\nnew", "r2", 24, 2), null))
        }
    }
    @Test fun roleplayAndSubagentsExitBeforeAnySettingsSecretsDatabaseOrNetworkAccess() {
        assertNull(OpenVikingRunCapture.create(app, "roleplay", "run", "m", "Fictional event", roleplay = true))
        assertNull(OpenVikingRunCapture.create(app, "child", "run", "m", "Subagent output", roleplay = false, subagent = true))
        assertNull(OpenVikingRuntimeContext.create(app, "roleplay", AgentRunController(), roleplay = true, memoryEnabled = true))
        OpenVikingTools(app) { false }.use { tools ->
            AgentOpenVikingToolCatalog.names.forEach { name -> assertEquals("OPENVIKING_ROLEPLAY_BLOCKED",
                JSONObject(tools.execute(AgentModelClient.ToolCall(name, name, "{}")).content).getString("code")) }
        }
    }
    @Test fun outboxAndReceiptCommitIsAtomicWhenTransactionRollsBack() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(app).use { f ->
            val ledger = OpenVikingCaptureRepository(f.database, f.cipher, f.clock::get)
            ledger.stage(f.connection, "conversation", "run", listOf(OpenVikingCaptureRepository.Input("m", "durable preference")))
            try {
                f.database.withTransaction { ledger.finish(f.connection, "conversation", "run", 0, "eta", null); error("Simulated crash before commit") }
            } catch (_: IllegalStateException) { }
            assertEquals(1, ledger.finish(f.connection, "conversation", "run", 0, "eta", null))
        }
    }
}
