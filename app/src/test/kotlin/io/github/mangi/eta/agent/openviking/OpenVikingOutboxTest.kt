package io.github.mangi.eta.agent.openviking

import android.app.Application
import io.github.mangi.eta.data.model.openVikingHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpenVikingOutboxTest {
    @Test fun dedupSurvivesAcknowledgementAndPayloadsAreEncrypted() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            assertTrue(f.add(content = "private fixture phrase"))
            assertFalse(f.add(content = "private fixture phrase"))
            val session = f.outbox.dao.sessions(f.connection).single()
            val row = f.outbox.dao.pending(session.id).single()
            assertFalse(row.payloadCipher.toString(Charsets.UTF_8).contains("private fixture phrase"))
            val op = f.outbox.claimBatch(session)!!
            assertEquals("private fixture phrase", f.outbox.request<BatchAddMessageRequest>(op).messages.single().content)
            f.outbox.acknowledge(op)
            assertFalse(f.add())
            assertEquals(0L, f.outbox.dao.queuedBytes(f.connection))
        }
    }

    @Test fun sendingOnCrashBecomesUncertainAndIsNeverReclaimed() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            f.add()
            f.outbox.claimBatch(f.outbox.dao.sessions(f.connection).single())
            f.outbox.recoverSending(f.connection)
            assertEquals("UNCERTAIN", f.outbox.dao.activeOperations(f.connection).single().state)
            val api = FakeOpenVikingApi()
            assertEquals(OpenVikingSyncOutcome.UNCERTAIN, f.coordinator.sync(f.settings, api, force = true))
            assertEquals(0, api.creates.get() + api.batches.get() + api.commits.get())
        }
    }

    @Test fun evictsWholeAutoTurnsButPreservesExplicitRecords() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication(), quota = 1000).use { f ->
            f.add("auto1", "a".repeat(250), turn = "auto-turn")
            f.add("auto2", "b".repeat(250), turn = "auto-turn")
            f.add("explicit", "c".repeat(400), priority = OpenVikingPriority.EXPLICIT)
            val session = f.outbox.dao.sessions(f.connection).single()
            val pending = f.outbox.dao.pending(session.id)
            assertEquals(1, pending.size)
            assertEquals(OpenVikingPriority.EXPLICIT.value, pending.single().priority)
            assertEquals(2L, f.outbox.dao.syncState(f.connection)!!.evictedCount)
            try { f.add("another", "d".repeat(600), priority = OpenVikingPriority.EXPLICIT); fail("Should reject full queue") }
            catch (_: OpenVikingQueueFull) { }
            assertEquals(1, f.outbox.dao.pending(session.id).size)
        }
    }

    @Test fun connectionIsolationAndAadBindingPreventCrossUse() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            f.add()
            val other = f.settings.copy(actorPeer = "eta-other").connectionId()!!
            assertTrue(f.outbox.dao.sessions(other).isEmpty())
            val row = f.outbox.dao.pending(f.outbox.dao.sessions(f.connection).single().id).single()
            try { f.cipher.decrypt(row.payloadCipher, "wrong-connection:outbox:${row.id}:v1"); fail("AAD must be bound") }
            catch (_: javax.crypto.AEADBadTagException) { }
            assertEquals(openVikingHash("m1"), row.sourceMessageKey)
        }
    }

    @Test fun expiredAutoDataIsPrunedButExplicitDataIsRetained() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            f.add("auto")
            f.add("explicit", priority = OpenVikingPriority.EXPLICIT)
            f.clock.addAndGet(8L * 24 * 60 * 60 * 1000)
            f.outbox.acquireLease(f.connection, "test")
            f.outbox.recoverSending(f.connection)
            assertEquals(1, f.outbox.dao.pending(f.outbox.dao.sessions(f.connection).single().id).size)
        }
    }

    @Test fun retryingBatchCannotBeReclaimedOrEvictedWhileItsRequestIsRetained() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication(), quota = 1000).use { f ->
            f.add("retry", "a".repeat(400))
            val session = f.outbox.dao.sessions(f.connection).single()
            val operation = f.outbox.claimBatch(session)!!
            f.outbox.fail(operation, OpenVikingApiException(OpenVikingErrorCode.NETWORK_ERROR))
            assertTrue(f.outbox.dao.pending(session.id).isEmpty())
            assertEquals("RETRY", f.outbox.dao.operationMessages(operation.id).single().state)
            try { f.add("new", "b".repeat(600)); fail("Must not evict a retained retry request") }
            catch (_: OpenVikingQueueFull) { }
            val retry = f.outbox.retry(f.outbox.dao.activeOperations(f.connection).single())
            assertEquals("a".repeat(400), f.outbox.request<BatchAddMessageRequest>(retry).messages.single().content)
        }
    }

    @Test fun quotaCannotEvictHalfOfTheTurnBeingExtended() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication(), quota = 1000).use { f ->
            f.add("first", "a".repeat(400), turn = "same-turn")
            try { f.add("second", "b".repeat(600), turn = "same-turn"); fail("Should reject partial-turn eviction") }
            catch (_: OpenVikingQueueFull) { }
            val session = f.outbox.dao.sessions(f.connection).single()
            assertEquals(openVikingHash("first"), f.outbox.dao.pending(session.id).single().sourceMessageKey)
        }
    }

    @Test fun batchesRespectTheSerializedByteLimitAndKeepTheRemainingMessagesQueued() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            repeat(7) { f.add("large-$it", "x".repeat(12 * 1024)) }
            val session = f.outbox.dao.sessions(f.connection).single()
            val operation = f.outbox.claimBatch(session)!!
            val request = f.outbox.request<BatchAddMessageRequest>(operation)
            val json = Json { encodeDefaults = true; explicitNulls = false }
            assertTrue(request.messages.size in 1..20)
            assertTrue(json.encodeToString(request).toByteArray(Charsets.UTF_8).size <= 64 * 1024)
            assertEquals(7 - request.messages.size, f.outbox.dao.pending(session.id).size)
        }
    }
}
