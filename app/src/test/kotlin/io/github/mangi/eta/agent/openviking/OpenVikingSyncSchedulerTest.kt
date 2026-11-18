package io.github.mangi.eta.agent.openviking

import android.app.Application
import io.github.mangi.eta.data.repository.OpenVikingPayloadCipher
import io.github.mangi.eta.data.repository.OpenVikingSecretsUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpenVikingSyncSchedulerTest {
    @Test fun plansUploadIdleCommitAndSparseTaskPollingThenStopsWhenIdle() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            assertNull(OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
            f.add()
            assertEquals(f.clock.get() + 1000, OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
            val api = FakeOpenVikingApi()
            f.coordinator.sync(f.settings, api)
            val session = f.database.openVikingDao().sessions(f.connection).single()
            assertEquals(session.lastAppendAt + f.settings.idleCommitMinutes * 60_000L,
                OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
            f.coordinator.sync(f.settings, api, force = true)
            val operation = f.database.openVikingDao().activeOperations(f.connection).single()
            assertEquals(operation.nextAttemptAt, OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
            f.clock.set(operation.nextAttemptAt + 1)
            f.coordinator.sync(f.settings, api, pollOnly = true)
            assertNull(OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
        }
    }
    @Test fun uncertainAndAuthenticationFailuresNeverCreateAnAutomaticReplayLoop() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            f.add()
            val api = FakeOpenVikingApi().apply { batchFailure = OpenVikingApiException(OpenVikingErrorCode.TIMEOUT, mayHaveReachedServer = true) }
            f.coordinator.sync(f.settings, api)
            assertNull(OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
            val state = f.database.openVikingDao().syncState(f.connection)!!
            f.database.openVikingDao().updateSyncState(state.copy(pausedReason = "AUTH_FAILED"))
            assertNull(OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get(), force = true))
            assertEquals(1, api.batches.get())
        }
    }
    @Test fun missingPayloadKeyPausesBackgroundWorkAndPreservesPendingCiphertext() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            f.add()
            val session = f.database.openVikingDao().sessions(f.connection).single()
            f.database.openVikingDao().updateSession(session.copy(remoteCreated = true))
            val unavailable = object : OpenVikingPayloadCipher {
                override fun encrypt(plaintext: ByteArray, aad: String): ByteArray = error("Not used")
                override fun decrypt(ciphertext: ByteArray, aad: String): ByteArray = throw OpenVikingSecretsUnavailable()
            }
            try {
                OpenVikingSyncCoordinator(OpenVikingOutboxRepository(f.database, unavailable, f.clock::get), f.clock::get)
                    .sync(f.settings, FakeOpenVikingApi())
                fail("Missing payload key must be surfaced")
            } catch (_: OpenVikingSecretsUnavailable) { }
            assertEquals("ENCRYPTION_UNAVAILABLE", f.database.openVikingDao().syncState(f.connection)!!.pausedReason)
            assertNull(OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
            assertTrue(f.database.openVikingDao().pending(session.id).single().payloadCipher.isNotEmpty())
        }
    }
    @Test fun interruptedSendingOperationWakesAfterLeaseAndIsQuarantinedWithoutReplay() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            f.add()
            val session = f.database.openVikingDao().sessions(f.connection).single()
            assertTrue(f.outbox.acquireLease(f.connection, "crashed-worker"))
            f.outbox.claimBatch(session)
            val lease = f.database.openVikingDao().syncState(f.connection)!!.leaseUntil
            assertEquals(lease + 1_000, OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
            f.clock.set(lease + 1_000)
            val api = FakeOpenVikingApi()
            assertEquals(OpenVikingSyncOutcome.UNCERTAIN, f.coordinator.sync(f.settings, api))
            assertEquals(0, api.batches.get())
            assertNull(OpenVikingSyncScheduler.nextDue(f.database, f.settings, f.clock.get()))
        }
    }
}
