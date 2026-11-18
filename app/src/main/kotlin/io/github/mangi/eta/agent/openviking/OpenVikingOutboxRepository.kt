package io.github.mangi.eta.agent.openviking

import androidx.room.withTransaction
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.OpenVikingOperationEntity
import io.github.mangi.eta.data.db.OpenVikingOutboxEntity
import io.github.mangi.eta.data.db.OpenVikingSessionEntity
import io.github.mangi.eta.data.db.OpenVikingSyncStateEntity
import io.github.mangi.eta.data.model.openVikingHash
import io.github.mangi.eta.data.repository.OpenVikingPayloadCipher
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class OpenVikingPriority(val value: Int) { AUTO(0), EXPLICIT(1) }
internal class OpenVikingQueueFull : IllegalStateException("OpenViking local queue full")

/** Storage boundary. Callers must supply sanitized text with explicit provenance. */
internal class OpenVikingOutboxRepository(
    internal val database: EtaDatabase,
    private val cipher: OpenVikingPayloadCipher,
    private val now: () -> Long = System::currentTimeMillis,
    private val quotaBytes: Long = 10L * 1024 * 1024,
) {
    internal val dao = database.openVikingDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    suspend fun enqueue(
        connectionId: String, localScopeKey: String, sourceMessageKey: String,
        captureVersion: Int, message: AddMessageRequest, tokenEstimate: Int,
        priority: OpenVikingPriority = OpenVikingPriority.AUTO, epoch: Int = 0,
    ): Boolean = database.withTransaction {
        require(connectionId.isNotBlank() && localScopeKey.isNotBlank() && localScopeKey.length <= 512)
        require(sourceMessageKey.isNotBlank() && captureVersion > 0 && tokenEstimate in 0..64000)
        require(epoch >= 0)
        require(message.role in setOf("user", "assistant") && message.content.isNotBlank())
        val turnId = requireNotNull(message.turnId)
        require(turnId.isNotBlank() && turnId.length <= 256)
        val encoded = json.encodeToString(message).toByteArray(Charsets.UTF_8)
        require(encoded.size <= 16 * 1024) { "OpenViking message too large" }
        val scope = openVikingHash(localScopeKey)
        if (dao.scopedSession(connectionId, scope, epoch) == null) {
            // A local reset must never accidentally reuse an old remote session / sequence range.
            val id = "eta-${UUID.randomUUID()}"
            dao.insertSession(OpenVikingSessionEntity(id, connectionId, scope, epoch, id))
        }
        val session = checkNotNull(dao.scopedSession(connectionId, scope, epoch))
        val sessionId = session.id
        val source = openVikingHash(sourceMessageKey)
        if (dao.source(sessionId, source, captureVersion) != null) return@withTransaction false
        val id = UUID.randomUUID().toString()
        val encrypted = cipher.encrypt(encoded, aad(connectionId, "outbox", id))
        if (encrypted.size.toLong() > quotaBytes) throw OpenVikingQueueFull()
        ensureState(connectionId)
        for (turn in dao.evictableTurns(connectionId)) {
            // Never evict the earlier half of the turn that is currently being extended.
            if (turn.sessionId == sessionId && turn.turnId == turnId) continue
            if (turn.expiresAt > now() && dao.queuedBytes(connectionId) + encrypted.size <= quotaBytes) continue
            val removed = dao.deleteAutoTurn(turn.sessionId, turn.turnId)
            val state = checkNotNull(dao.syncState(connectionId))
            dao.updateSyncState(state.copy(evictedCount = state.evictedCount + removed))
        }
        if (dao.queuedBytes(connectionId) + encrypted.size > quotaBytes) throw OpenVikingQueueFull()
        dao.insertOutbox(OpenVikingOutboxEntity(
            id, sessionId, connectionId, source, captureVersion, session.nextSeq,
            turnId, encrypted, tokenEstimate, encrypted.size, priority.value,
            createdAt = now(), expiresAt = now() + 7L * 24 * 60 * 60 * 1000,
        ))
        dao.updateSession(session.copy(nextSeq = session.nextSeq + 1))
        true
    }

    private suspend fun ensureState(connection: String) {
        dao.insertSyncState(OpenVikingSyncStateEntity(connection))
    }

    suspend fun acquireLease(connection: String, owner: String): Boolean = database.withTransaction {
        ensureState(connection)
        val state = checkNotNull(dao.syncState(connection))
        if (state.leaseUntil > now() && state.leaseOwner != owner) return@withTransaction false
        dao.updateSyncState(state.copy(leaseOwner = owner, leaseUntil = now() + LEASE_MS))
        true
    }

    suspend fun renewLease(connection: String, owner: String) = database.withTransaction {
        val state = checkNotNull(dao.syncState(connection))
        check(state.leaseOwner == owner) { "OpenViking lease lost" }
        dao.updateSyncState(state.copy(leaseUntil = now() + LEASE_MS))
    }

    suspend fun releaseLease(connection: String, owner: String) = database.withTransaction {
        dao.syncState(connection)?.takeIf { it.leaseOwner == owner }?.let {
            dao.updateSyncState(it.copy(leaseOwner = null, leaseUntil = 0))
        }
    }

    /** Call only while holding the durable lease: a prior process may have sent any SENDING request. */
    suspend fun recoverSending(connection: String) = database.withTransaction {
        dao.activeOperations(connection).filter { it.state == "SENDING" }.forEach { op ->
            dao.updateOperation(op.copy(state = "UNCERTAIN", errorCode = "PROCESS_INTERRUPTED"))
            dao.operationMessages(op.id).forEach { dao.updateOutbox(it.copy(state = "UNCERTAIN")) }
        }
        for (turn in dao.evictableTurns(connection).filter { it.expiresAt <= now() }) {
            val removed = dao.deleteAutoTurn(turn.sessionId, turn.turnId)
            val state = checkNotNull(dao.syncState(connection))
            dao.updateSyncState(state.copy(evictedCount = state.evictedCount + removed))
        }
    }

    suspend fun claimBatch(session: OpenVikingSessionEntity): OpenVikingOperationEntity? = database.withTransaction {
        val messages = mutableListOf<AddMessageRequest>()
        val selected = mutableListOf<OpenVikingOutboxEntity>()
        for (row in dao.pending(session.id)) {
            val text = cipher.decrypt(row.payloadCipher, aad(row.connectionId, "outbox", row.id)).toString(Charsets.UTF_8)
            val message = json.decodeFromString<AddMessageRequest>(text)
            val candidate = BatchAddMessageRequest(messages + message)
            if (json.encodeToString(candidate).toByteArray(Charsets.UTF_8).size > 64 * 1024) break
            messages += message
            selected += row
        }
        if (selected.isEmpty()) return@withTransaction null
        val op = newOperation(session, "BATCH", json.encodeToString(BatchAddMessageRequest(messages)), selected.last().seq)
        dao.insertOperation(op)
        selected.forEach { dao.updateOutbox(it.copy(state = "SENDING", operationId = op.id)) }
        op
    }

    suspend fun claimCreate(session: OpenVikingSessionEntity): OpenVikingOperationEntity = database.withTransaction {
        newOperation(session, "CREATE", json.encodeToString(CreateSessionRequest(session.remoteSessionId))).also { dao.insertOperation(it) }
    }

    suspend fun claimCommit(session: OpenVikingSessionEntity): OpenVikingOperationEntity = database.withTransaction {
        newOperation(session, "COMMIT", json.encodeToString(CommitRequest()), session.ackedSeq).also { dao.insertOperation(it) }
    }

    private fun newOperation(session: OpenVikingSessionEntity, kind: String, body: String, through: Long = 0): OpenVikingOperationEntity {
        val id = UUID.randomUUID().toString()
        return OpenVikingOperationEntity(id, session.connectionId, session.id, kind,
            requestCipher = cipher.encrypt(body.toByteArray(Charsets.UTF_8), aad(session.connectionId, "operations", id)),
            throughSeq = through, createdAt = now())
    }

    internal inline fun <reified T> request(operation: OpenVikingOperationEntity): T =
        decodeRequest(operation)

    // Separate non-inline access keeps cryptographic fields private to this repository.
    @PublishedApi internal fun requestText(operation: OpenVikingOperationEntity): String = cipher.decrypt(
        operation.requestCipher, aad(operation.connectionId, "operations", operation.id),
    ).toString(Charsets.UTF_8)

    @PublishedApi internal inline fun <reified T> decodeRequest(operation: OpenVikingOperationEntity): T =
        Json.decodeFromString<T>(requestText(operation))

    suspend fun retry(operation: OpenVikingOperationEntity): OpenVikingOperationEntity = database.withTransaction {
        check(operation.state == "RETRY")
        val sending = operation.copy(state = "SENDING", errorCode = null)
        dao.updateOperation(sending)
        dao.operationMessages(operation.id).forEach { dao.updateOutbox(it.copy(state = "SENDING")) }
        sending
    }

    suspend fun acknowledge(operation: OpenVikingOperationEntity, taskId: String? = null) = database.withTransaction {
        val session = checkNotNull(dao.session(operation.sessionId))
        when (operation.kind) {
            "CREATE" -> dao.updateSession(session.copy(remoteCreated = true))
            "BATCH" -> {
                dao.operationMessages(operation.id).forEach {
                    dao.updateOutbox(it.copy(state = "ACKNOWLEDGED", payloadCipher = byteArrayOf()))
                }
                dao.updateSession(session.copy(ackedSeq = maxOf(session.ackedSeq, operation.throughSeq), lastAppendAt = now()))
            }
            "COMMIT" -> {
                require(!taskId.isNullOrBlank())
                dao.updateSession(session.copy(archivedSeq = maxOf(session.archivedSeq, operation.throughSeq), lastCommitAt = now()))
            }
        }
        dao.updateOperation(operation.copy(state = if (taskId == null) "ACKNOWLEDGED" else "POLLING",
            requestCipher = byteArrayOf(), taskId = taskId, nextAttemptAt = if (taskId == null) 0 else now() + 10000))
        val state = checkNotNull(dao.syncState(operation.connectionId))
        dao.updateSyncState(state.copy(lastSuccessAt = now(), lastError = null))
    }

    suspend fun fail(operation: OpenVikingOperationEntity, failure: OpenVikingApiException) = database.withTransaction {
        val uncertain = failure.mayHaveReachedServer
        val attempt = operation.attemptCount + 1
        val backoff = (30000L shl attempt.coerceAtMost(7)).coerceAtMost(3600000L)
        dao.updateOperation(operation.copy(state = if (uncertain) "UNCERTAIN" else "RETRY",
            errorCode = failure.code.name, attemptCount = attempt, nextAttemptAt = now() + backoff))
        dao.operationMessages(operation.id).forEach {
            // The persisted operation owns these messages until its retry is resolved. Keeping
            // them out of PENDING prevents quota eviction or a second claim from orphaning it.
            dao.updateOutbox(it.copy(state = if (uncertain) "UNCERTAIN" else "RETRY"))
        }
        val state = checkNotNull(dao.syncState(operation.connectionId))
        dao.updateSyncState(state.copy(lastError = failure.code.name,
            pausedReason = when {
                failure.code == OpenVikingErrorCode.AUTH_FAILED -> "AUTH_FAILED"
                failure.code == OpenVikingErrorCode.PROTOCOL_ERROR && failure.httpStatus in setOf(400, 404, 405, 413, 422) -> "PROTOCOL_ERROR"
                else -> state.pausedReason
            }))
    }

    suspend fun pollResult(operation: OpenVikingOperationEntity, response: TaskStatusResponse) = database.withTransaction {
        val state = checkNotNull(dao.syncState(operation.connectionId))
        when {
            response.succeeded -> {
                dao.updateOperation(operation.copy(state = "COMPLETED"))
                dao.session(operation.sessionId)?.let { dao.updateSession(it.copy(extractedSeq = maxOf(it.extractedSeq, operation.throughSeq))) }
                dao.updateSyncState(state.copy(lastSuccessAt = now(), lastError = null))
            }
            response.failed -> {
                dao.updateOperation(operation.copy(state = "FAILED", errorCode = "EXTRACTION_FAILED"))
                dao.updateSyncState(state.copy(lastError = "EXTRACTION_FAILED"))
            }
            else -> deferPoll(operation)
        }
    }

    suspend fun deferPoll(operation: OpenVikingOperationEntity) {
        val delay = longArrayOf(10000, 30000, 60000, 120000)[(operation.attemptCount + 1).coerceAtMost(3)]
        dao.updateOperation(operation.copy(attemptCount = operation.attemptCount + 1, nextAttemptAt = now() + delay))
    }

    companion object {
        const val LEASE_MS = 120000L
        private fun aad(connection: String, table: String, id: String): String = "$connection:$table:$id:v1"
    }
}
