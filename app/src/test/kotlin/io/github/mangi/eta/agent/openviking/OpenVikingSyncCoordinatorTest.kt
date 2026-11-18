package io.github.mangi.eta.agent.openviking

import android.app.Application
import io.github.mangi.eta.data.repository.OpenVikingPayloadCipher
import io.github.mangi.eta.data.repository.OpenVikingSecretsUnavailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpenVikingSyncCoordinatorTest {
    @Test fun thresholdCommitAndSparseTaskPollingHaveSeparateWatermarks() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi()
            repeat(8) { f.add("m$it") }
            f.coordinator.sync(f.settings, api)
            assertEquals(1, api.commits.get())
            assertEquals(0, api.polls.get())
            val archived = f.outbox.dao.sessions(f.connection).single()
            assertEquals(8L, archived.archivedSeq)
            assertEquals(0L, archived.extractedSeq)
            f.coordinator.sync(f.settings, api)
            assertEquals(0, api.polls.get())
            f.clock.addAndGet(10000)
            f.coordinator.sync(f.settings, api)
            assertEquals(1, api.polls.get())
            assertEquals(8L, f.outbox.dao.sessions(f.connection).single().extractedSeq)
            assertEquals(1, api.commits.get())
        }
    }

    @Test fun ambiguousTimeoutIsNotReplayedEvenByExplicitSync() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi().apply {
                batchFailure = OpenVikingApiException(OpenVikingErrorCode.TIMEOUT, mayHaveReachedServer = true)
            }
            f.add()
            f.coordinator.sync(f.settings, api)
            api.batchFailure = null
            f.clock.addAndGet(3600000)
            assertEquals(OpenVikingSyncOutcome.UNCERTAIN, f.coordinator.sync(f.settings, api, force = true))
            assertEquals(1, api.batches.get())
            assertEquals(0, api.commits.get())
        }
    }

    @Test fun knownUnsentFailuresRetryOnlyAfterBackoff() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi().apply { batchFailure = OpenVikingApiException(OpenVikingErrorCode.NETWORK_ERROR) }
            f.add()
            f.coordinator.sync(f.settings, api)
            api.batchFailure = null
            f.coordinator.sync(f.settings, api)
            assertEquals(1, api.batches.get())
            f.clock.addAndGet(61000)
            f.coordinator.sync(f.settings, api)
            assertEquals(2, api.batches.get())
            assertEquals(1, api.uploaded.size)
        }
    }

    @Test fun separateCoordinatorInstancesRemainSingleFlight() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi().apply { delayMs = 10 }
            repeat(25) { f.add("m$it") }
            val other = OpenVikingSyncCoordinator(f.outbox, f.clock::get)
            listOf(async { f.coordinator.sync(f.settings, api) }, async { other.sync(f.settings, api) }).awaitAll()
            assertEquals(1, api.maxInFlight.get())
            assertEquals(1, api.creates.get())
            assertEquals(25, api.uploaded.size)
            assertEquals(1, api.commits.get())
        }
    }

    @Test fun smallTurnsDoNotCommitUntilTokenOrIdleThreshold() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi()
            f.add(tokens = 20)
            f.coordinator.sync(f.settings, api)
            assertEquals(0, api.commits.get())
            f.clock.addAndGet(5 * 60000L)
            f.coordinator.sync(f.settings, api)
            assertEquals(1, api.commits.get())
        }
    }

    @Test fun failedExtractionDoesNotRecommitAlreadyArchivedMessages() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi().apply { taskStatus = "failed" }
            f.add(priority = OpenVikingPriority.EXPLICIT)
            f.coordinator.sync(f.settings, api)
            f.clock.addAndGet(10000)
            f.coordinator.sync(f.settings, api)
            f.coordinator.sync(f.settings, api, force = true)
            assertEquals(1, api.commits.get())
            assertEquals("EXTRACTION_FAILED", f.outbox.dao.syncState(f.connection)!!.lastError)
        }
    }

    @Test fun disabledAndEmptyConnectionsNeverMakeNetworkRequests() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi()
            f.coordinator.sync(f.settings, api, force = true)
            f.add()
            f.coordinator.sync(f.settings.copy(enabled = false), api, force = true)
            assertEquals(0, api.creates.get() + api.batches.get() + api.commits.get())
        }
    }

    @Test fun refreshOnlyDoesNotUploadOrCommitPendingMessages() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            f.add(tokens = 4000)
            val api = FakeOpenVikingApi()
            f.coordinator.sync(f.settings, api, pollOnly = true)
            assertEquals(0, api.creates.get() + api.batches.get() + api.commits.get())
        }
    }

    @Test fun normalThresholdCommitsRespectMinimumInterval() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi()
            f.add("first", tokens = 4000)
            f.coordinator.sync(f.settings, api)
            f.clock.addAndGet(10000)
            f.coordinator.sync(f.settings, api, pollOnly = true)
            f.add("second", tokens = 4000)
            f.coordinator.sync(f.settings, api)
            assertEquals(1, api.commits.get())
            f.clock.addAndGet(50000)
            f.coordinator.sync(f.settings, api)
            assertEquals(2, api.commits.get())
        }
    }

    @Test fun runningTaskPollsBackOffInsteadOfSpinning() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi().apply { taskStatus = "running" }
            f.add(priority = OpenVikingPriority.EXPLICIT)
            f.coordinator.sync(f.settings, api)
            f.clock.addAndGet(10000)
            f.coordinator.sync(f.settings, api)
            assertEquals(1, api.polls.get())
            f.clock.addAndGet(29999)
            f.coordinator.sync(f.settings, api)
            assertEquals(1, api.polls.get())
            f.clock.incrementAndGet()
            f.coordinator.sync(f.settings, api)
            assertEquals(2, api.polls.get())
            assertEquals(f.clock.get() + 60000, f.outbox.dao.activeOperations(f.connection).single().nextAttemptAt)
        }
    }

    @Test fun persistedBackgroundTaskDoesNotBlockLaterOrderedUploads() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            val api = FakeOpenVikingApi().apply { taskStatus = "running" }
            f.add("first", tokens = 4000)
            f.coordinator.sync(f.settings, api)
            f.add("second")
            val restarted = OpenVikingSyncCoordinator(f.outbox, f.clock::get)
            restarted.sync(f.settings, api)
            assertEquals(2, api.uploaded.size)
            assertEquals(1, api.commits.get())
            assertEquals(0, api.polls.get())
            f.clock.addAndGet(10000)
            api.taskStatus = "completed"
            restarted.sync(f.settings, api, pollOnly = true)
            assertEquals(1L, f.outbox.dao.sessions(f.connection).single().extractedSeq)
            assertEquals(1, api.polls.get())
        }
    }

    @Test fun unreadableOperationPayloadIsReportedWithoutSendingOrBlindlyAssumingDelivery() = runBlocking(Dispatchers.IO) {
        OpenVikingTestFixture(RuntimeEnvironment.getApplication()).use { f ->
            f.add()
            val unavailable = object : OpenVikingPayloadCipher {
                override fun encrypt(plaintext: ByteArray, aad: String) = f.cipher.encrypt(plaintext, aad)
                override fun decrypt(ciphertext: ByteArray, aad: String): ByteArray = throw OpenVikingSecretsUnavailable()
            }
            val repository = OpenVikingOutboxRepository(f.database, unavailable, f.clock::get)
            val api = FakeOpenVikingApi()
            try { OpenVikingSyncCoordinator(repository, f.clock::get).sync(f.settings, api); fail("Must surface unavailable encryption") }
            catch (_: OpenVikingSecretsUnavailable) { }
            assertEquals(0, api.creates.get() + api.batches.get())
            assertEquals("RETRY", f.outbox.dao.activeOperations(f.connection).single().state)
            assertEquals(0L, f.outbox.dao.syncState(f.connection)!!.leaseUntil)
        }
    }
}
