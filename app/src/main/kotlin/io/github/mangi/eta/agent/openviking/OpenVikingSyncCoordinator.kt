package io.github.mangi.eta.agent.openviking

import androidx.room.withTransaction
import io.github.mangi.eta.data.db.OpenVikingOperationEntity
import io.github.mangi.eta.data.model.OpenVikingSettings
import io.github.mangi.eta.data.repository.OpenVikingSecretsUnavailable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class OpenVikingSyncOutcome { IDLE, SYNCED, BUSY, WAITING, UNCERTAIN, PAUSED }

/** All callers, including background jobs, share one ordered queue per connection. */
internal class OpenVikingSyncCoordinator(
    private val outbox: OpenVikingOutboxRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync(settings: OpenVikingSettings, api: OpenVikingSyncApi, force: Boolean = false, pollOnly: Boolean = false): OpenVikingSyncOutcome =
        withContext(Dispatchers.IO) {
            if (!settings.enabled) return@withContext OpenVikingSyncOutcome.PAUSED
            settings.validate()
            val connection = checkNotNull(settings.connectionId())
            locks.getOrPut(connection) { Mutex() }.withLock {
                val owner = UUID.randomUUID().toString()
                if (!outbox.acquireLease(connection, owner)) return@withLock OpenVikingSyncOutcome.BUSY
                try {
                    runSync(settings, connection, owner, api, force, pollOnly)
                } catch (failure: OpenVikingSecretsUnavailable) {
                    // A lost payload key cannot heal through transport retries. Preserve the
                    // ciphertext and delivery evidence, surface repair, and stop automatic jobs.
                    withContext(NonCancellable) { outbox.database.withTransaction {
                        outbox.dao.syncState(connection)?.let {
                            outbox.dao.updateSyncState(it.copy(lastError = "ENCRYPTION_UNAVAILABLE", pausedReason = "ENCRYPTION_UNAVAILABLE"))
                        }
                    } }
                    throw failure
                } finally {
                    withContext(NonCancellable) { outbox.releaseLease(connection, owner) }
                }
            }
        }

    /** Explicit sync can unpause authentication errors, but never resolves ambiguous delivery. */
    suspend fun resumeAfterConfigurationCheck(connection: String) = withContext(Dispatchers.IO) {
        outbox.database.withTransaction {
            outbox.dao.syncState(connection)?.let { outbox.dao.updateSyncState(it.copy(pausedReason = null)) }
        }
    }

    private suspend fun runSync(
        settings: OpenVikingSettings, connection: String, owner: String,
        api: OpenVikingSyncApi, force: Boolean, pollOnly: Boolean,
    ): OpenVikingSyncOutcome {
        outbox.recoverSending(connection)
        if (outbox.dao.syncState(connection)?.pausedReason != null) return OpenVikingSyncOutcome.PAUSED
        val active = outbox.dao.activeOperations(connection)
        // Poll known tasks even when another operation needs manual reconciliation.
        for (op in active.filter { it.state == "POLLING" }) {
            if (now() < op.nextAttemptAt) continue
            outbox.renewLease(connection, owner)
            currentCoroutineContext().ensureActive()
            try { outbox.pollResult(op, api.task(checkNotNull(op.taskId))) }
            catch (failure: OpenVikingApiException) {
                outbox.deferPoll(op)
                recordReadFailure(connection, failure)
                return OpenVikingSyncOutcome.WAITING
            }
        }
        val remaining = outbox.dao.activeOperations(connection)
        if (remaining.any { it.state == "UNCERTAIN" }) return OpenVikingSyncOutcome.UNCERTAIN
        val extracting = remaining.any { it.state == "POLLING" }
        if (pollOnly) return if (extracting) OpenVikingSyncOutcome.WAITING else OpenVikingSyncOutcome.IDLE
        for (op in remaining.filter { it.state == "RETRY" }) {
            if (!force && now() < op.nextAttemptAt) return OpenVikingSyncOutcome.WAITING
            outbox.renewLease(connection, owner)
            if (!send(outbox.retry(op), api)) return OpenVikingSyncOutcome.WAITING
            if (op.kind == "COMMIT") return OpenVikingSyncOutcome.WAITING
        }
        var changed = false
        for (initial in outbox.dao.sessions(connection)) {
            currentCoroutineContext().ensureActive()
            var session = checkNotNull(outbox.dao.session(initial.id))
            if (!session.remoteCreated && outbox.dao.pending(session.id).isNotEmpty()) {
                outbox.renewLease(connection, owner)
                if (!send(outbox.claimCreate(session), api)) return OpenVikingSyncOutcome.WAITING
                session = checkNotNull(outbox.dao.session(session.id))
            }
            while (true) {
                currentCoroutineContext().ensureActive()
                outbox.renewLease(connection, owner)
                val batch = outbox.claimBatch(session) ?: break
                if (!send(batch, api)) return OpenVikingSyncOutcome.WAITING
                changed = true
            }
            session = checkNotNull(outbox.dao.session(session.id))
            val tokens = outbox.dao.pendingTokens(session.id, session.archivedSeq)
            val turns = outbox.dao.pendingTurns(session.id, session.archivedSeq)
            val oldest = outbox.dao.oldestUncommitted(session.id, session.archivedSeq)
            val explicit = outbox.dao.explicitPending(session.id, session.archivedSeq) > 0
            val due = tokens >= settings.commitTokenThreshold || turns >= settings.commitTurnThreshold ||
                (turns > 0 && now() - session.lastAppendAt >= settings.idleCommitMinutes * 60000L) ||
                (oldest > 0 && now() - oldest >= 24L * 60 * 60 * 1000)
            val intervalPassed = session.lastCommitAt == 0L || now() - session.lastCommitAt >= settings.minCommitIntervalSeconds * 1000L
            if (session.ackedSeq > session.archivedSeq && turns > 0 &&
                ((due && intervalPassed) || force || explicit)) {
                outbox.renewLease(connection, owner)
                send(outbox.claimCommit(session), api)
                return OpenVikingSyncOutcome.WAITING
            }
        }
        // Phase 1 has already archived through_seq. A background Phase 2 task must not block
        // later ordered appends; the server owns extraction concurrency, not the mobile client.
        return if (changed) OpenVikingSyncOutcome.SYNCED else if (extracting) OpenVikingSyncOutcome.WAITING else OpenVikingSyncOutcome.IDLE
    }

    private suspend fun send(operation: OpenVikingOperationEntity, api: OpenVikingSyncApi): Boolean {
        currentCoroutineContext().ensureActive()
        val session = checkNotNull(outbox.dao.session(operation.sessionId))
        try {
            val task = when (operation.kind) {
                "CREATE" -> { api.createSession(outbox.request(operation)); null }
                "BATCH" -> { api.addMessages(session.remoteSessionId, outbox.request(operation)); null }
                "COMMIT" -> api.commit(session.remoteSessionId, outbox.request(operation)).taskId
                else -> error("Unsupported OpenViking operation")
            }
            withContext(NonCancellable) { outbox.acknowledge(operation, task) }
            return true
        } catch (failure: OpenVikingApiException) {
            withContext(NonCancellable) { outbox.fail(operation, failure) }
            return false
        } catch (failure: OpenVikingSecretsUnavailable) {
            // Operation decryption occurs before the HTTP call. Keep this known-unsent request
            // protected for recovery and surface the explicit encryption-unavailable UI state.
            withContext(NonCancellable) {
                outbox.fail(operation, OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR))
            }
            throw failure
        } catch (failure: CancellationException) {
            withContext(NonCancellable) {
                outbox.fail(operation, OpenVikingApiException(OpenVikingErrorCode.CANCELLED, mayHaveReachedServer = true))
            }
            throw failure
        } catch (_: Exception) {
            // Includes local persistence failure after a successful HTTP call: never assume it was unsent.
            withContext(NonCancellable) {
                outbox.fail(operation, OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR, mayHaveReachedServer = true))
            }
            return false
        }
    }

    private suspend fun recordReadFailure(connection: String, failure: OpenVikingApiException) {
        outbox.database.withTransaction {
            outbox.dao.syncState(connection)?.let {
                outbox.dao.updateSyncState(it.copy(lastError = failure.code.name,
                    pausedReason = if (failure.code == OpenVikingErrorCode.AUTH_FAILED) "AUTH_FAILED" else it.pausedReason))
            }
        }
    }

    companion object { private val locks = ConcurrentHashMap<String, Mutex>() }
}
