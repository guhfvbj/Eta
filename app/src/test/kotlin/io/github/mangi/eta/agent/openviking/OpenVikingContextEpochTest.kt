package io.github.mangi.eta.agent.openviking

import android.app.Application
import io.github.mangi.eta.agent.memory.ConversationMemoryPins
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeMemoryLifecycle
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

/** Integration uses ModelClient -> Loop -> ContextSession, not synthetic pin-only callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpenVikingContextEpochTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "fixed", contextWindow = 128000)
    private fun memory(revision: String) = AgentMemorySnapshot("# 核心记忆\n$revision", revision, 20, 2)
    private fun provider(summary: Boolean = false, fail: Boolean = false) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            if (fail) throw AgentModelFailure("FIXTURE_FAILED", false, "fixture")
            return ProviderResponse(JSONObject().put("role", "assistant").put("content", if (summary) "保留已完成工作和事实。" else "answer").put("finish_reason", "stop"))
        }
    }
    @Test fun ordinaryRuntimeCheckpointsKeepPinsAndEpochButSuccessfulCompactionAdvancesOnce() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val cache = OpenVikingContextCache(f.database, f.cipher)
            val scope = "epoch-runtime-${System.nanoTime()}"
            val original = ConversationMemoryPins.contextFor(scope, memory("r1"), 128000)
            ConversationMemoryPins.recordOwnWrite(scope, "r2")
            val lifecycle = AgentRuntimeMemoryLifecycle(scope) { id -> runBlocking { cache.advance(f.connection, scope, id) } }
            var checkpoints = 0
            AgentModelClient.complete(config, "question", AgentModelClient.ToolExecutor { error("no tools") }, provider = provider(),
                onContextSnapshot = { checkpoints++; assertSame(original, ConversationMemoryPins.contextFor(scope, memory("r2"), 128000)) },
                onEvent = lifecycle::accept)
            assertTrue(checkpoints > 0)
            assertEquals(0, cache.epoch(f.connection, scope))
            val history = (1..6).flatMap { listOf(AgentModelClient.ConversationMessage("user", "question $it"), AgentModelClient.ConversationMessage("assistant", "facts ".repeat(1000))) }
            var completed: AgentEvent.ContextCompaction? = null
            AgentModelClient.complete(config, "", AgentModelClient.ToolExecutor { error("no tools") }, provider = provider(summary = true),
                compactOnly = true, history = history, onEvent = {
                    lifecycle.accept(it)
                    if (it is AgentEvent.ContextCompaction && it.phase == "completed") completed = it
                })
            assertNotNull(completed)
            assertEquals(1, cache.epoch(f.connection, scope))
            lifecycle.accept(completed!!)
            assertEquals(1, OpenVikingContextCache(f.database, f.cipher).epoch(f.connection, scope))
            assertNotSame(original, ConversationMemoryPins.contextFor(scope, memory("r2"), 128000))
        }
    }

    @Test fun failedCompactionLeavesThePinnedPrefixAndEpochIntact() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val cache = OpenVikingContextCache(f.database, f.cipher)
            val scope = "failed-${System.nanoTime()}"
            val pin = ConversationMemoryPins.contextFor(scope, memory("r1"), 128000)
            val lifecycle = AgentRuntimeMemoryLifecycle(scope) { id -> runBlocking { cache.advance(f.connection, scope, id) } }
            try {
                AgentModelClient.complete(config, "", AgentModelClient.ToolExecutor { error("no tools") }, compactOnly = true,
                    history = listOf(AgentModelClient.ConversationMessage("user", "old"), AgentModelClient.ConversationMessage("assistant", "facts ".repeat(5000))),
                    provider = provider(fail = true), onEvent = lifecycle::accept)
                fail("Compaction must fail")
            } catch (_: AgentModelExecutionException) { }
            assertEquals(0, cache.epoch(f.connection, scope))
            assertSame(pin, ConversationMemoryPins.contextFor(scope, memory("r1"), 128000))
        }
    }

    @Test fun durableArchiveRepairsAMissedCompletedEventWithoutAdvancingOnCheckpointReplay() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            var saved: AgentContextSnapshot? = null
            // Deliberately omit lifecycle.accept: simulate death after the durable snapshot but
            // before the runtime gets to its completed-event handler.
            AgentModelClient.complete(config, "", AgentModelClient.ToolExecutor { error("no tools") },
                compactOnly = true, history = listOf(AgentModelClient.ConversationMessage("user", "old"),
                    AgentModelClient.ConversationMessage("assistant", "facts ".repeat(5000))),
                provider = provider(summary = true), onContextSnapshot = { saved = it })
            val archive = AgentContextSnapshot.decode(saved!!.encode())!!.messages
                .last { it.contextSummary }.compactionEpochId
            assertTrue(archive.isNotBlank())
            val restarted = OpenVikingContextCache(f.database, f.cipher)
            assertEquals(0, restarted.epoch(f.connection, "recovered"))
            assertEquals(1, restarted.advance(f.connection, "recovered", archive))
            assertEquals(1, restarted.advance(f.connection, "recovered", archive))
            // A cleared history can generate fold-1 again. Its successful compaction still has
            // a fresh UUID; ordinary checkpoint replay of that UUID does not advance again.
            AgentModelClient.complete(config, "", AgentModelClient.ToolExecutor { error("no tools") },
                compactOnly = true, history = listOf(AgentModelClient.ConversationMessage("user", "old"),
                    AgentModelClient.ConversationMessage("assistant", "facts ".repeat(5000))),
                provider = provider(summary = true), onContextSnapshot = { saved = it })
            val next = saved!!.messages.last { it.contextSummary }
            assertEquals("fold-1", next.compactionArchiveId)
            assertNotEquals(archive, next.compactionEpochId)
            assertEquals(2, restarted.advance(f.connection, "recovered", next.compactionEpochId))
            assertEquals(2, restarted.advance(f.connection, "recovered", next.compactionEpochId))
        }
    }
}
