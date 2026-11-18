package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "openviking_sessions", indices = [
    Index(value = ["connection_id", "local_scope_key", "context_epoch"], unique = true),
    Index(value = ["remote_session_id"], unique = true),
])
internal data class OpenVikingSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "local_scope_key") val localScopeKey: String,
    @ColumnInfo(name = "context_epoch") val contextEpoch: Int = 0,
    @ColumnInfo(name = "remote_session_id") val remoteSessionId: String,
    @ColumnInfo(name = "remote_created") val remoteCreated: Boolean = false,
    @ColumnInfo(name = "next_seq") val nextSeq: Long = 1,
    @ColumnInfo(name = "acked_seq") val ackedSeq: Long = 0,
    @ColumnInfo(name = "archived_seq") val archivedSeq: Long = 0,
    @ColumnInfo(name = "extracted_seq") val extractedSeq: Long = 0,
    @ColumnInfo(name = "last_append_at") val lastAppendAt: Long = 0,
    @ColumnInfo(name = "last_commit_at") val lastCommitAt: Long = 0,
)

@Entity(tableName = "openviking_outbox", foreignKeys = [ForeignKey(
    entity = OpenVikingSessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE,
)], indices = [
    Index(value = ["session_id", "source_message_key", "capture_version"], unique = true),
    Index(value = ["session_id", "seq"], unique = true),
    Index(value = ["connection_id", "state", "created_at"]),
])
internal data class OpenVikingOutboxEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "source_message_key") val sourceMessageKey: String,
    @ColumnInfo(name = "capture_version") val captureVersion: Int,
    val seq: Long,
    @ColumnInfo(name = "turn_id") val turnId: String,
    @ColumnInfo(name = "payload_cipher", typeAffinity = ColumnInfo.BLOB) val payloadCipher: ByteArray,
    @ColumnInfo(name = "token_estimate") val tokenEstimate: Int,
    @ColumnInfo(name = "byte_count") val byteCount: Int,
    val priority: Int = 0,
    val state: String = "PENDING",
    @ColumnInfo(name = "operation_id") val operationId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)

@Entity(tableName = "openviking_operations", foreignKeys = [ForeignKey(
    entity = OpenVikingSessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE,
)], indices = [Index(value = ["session_id"]), Index(value = ["connection_id", "state"])])
internal data class OpenVikingOperationEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val kind: String,
    val state: String = "SENDING",
    @ColumnInfo(name = "request_cipher", typeAffinity = ColumnInfo.BLOB) val requestCipher: ByteArray,
    @ColumnInfo(name = "through_seq") val throughSeq: Long = 0,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int = 0,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long = 0,
    @ColumnInfo(name = "task_id") val taskId: String? = null,
    @ColumnInfo(name = "error_code") val errorCode: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** message_id is a runtime identity, deliberately NOT a foreign key to conversation_messages. */
@Entity(tableName = "openviking_injections", primaryKeys = ["connection_id", "local_scope_key", "message_id", "context_epoch"])
internal data class OpenVikingInjectionEntity(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "local_scope_key") val localScopeKey: String,
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "context_epoch") val contextEpoch: Int,
    val outcome: String,
    @ColumnInfo(name = "payload_cipher", typeAffinity = ColumnInfo.BLOB) val payloadCipher: ByteArray?,
    @ColumnInfo(name = "payload_version") val payloadVersion: Int = 1,
    @ColumnInfo(name = "token_count") val tokenCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "openviking_cache", primaryKeys = ["connection_id", "cache_kind", "cache_key"])
internal data class OpenVikingCacheEntity(
    @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "cache_kind") val cacheKind: String,
    @ColumnInfo(name = "cache_key") val cacheKey: String,
    @ColumnInfo(name = "payload_cipher", typeAffinity = ColumnInfo.BLOB) val payloadCipher: ByteArray,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)

@Entity(tableName = "openviking_sync_state")
internal data class OpenVikingSyncStateEntity(
    @PrimaryKey @ColumnInfo(name = "connection_id") val connectionId: String,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String? = null,
    @ColumnInfo(name = "lease_until") val leaseUntil: Long = 0,
    @ColumnInfo(name = "paused_reason") val pausedReason: String? = null,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: Long = 0,
    @ColumnInfo(name = "evicted_count") val evictedCount: Long = 0,
)

/** Kept alongside entities so migration SQL and Room declarations can be audited together. */
internal object OpenVikingSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS openviking_sessions (id TEXT NOT NULL PRIMARY KEY, connection_id TEXT NOT NULL, local_scope_key TEXT NOT NULL, context_epoch INTEGER NOT NULL, remote_session_id TEXT NOT NULL, remote_created INTEGER NOT NULL, next_seq INTEGER NOT NULL, acked_seq INTEGER NOT NULL, archived_seq INTEGER NOT NULL, extracted_seq INTEGER NOT NULL, last_append_at INTEGER NOT NULL, last_commit_at INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_openviking_sessions_connection_id_local_scope_key_context_epoch ON openviking_sessions(connection_id,local_scope_key,context_epoch)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_openviking_sessions_remote_session_id ON openviking_sessions(remote_session_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS openviking_outbox (id TEXT NOT NULL PRIMARY KEY, session_id TEXT NOT NULL, connection_id TEXT NOT NULL, source_message_key TEXT NOT NULL, capture_version INTEGER NOT NULL, seq INTEGER NOT NULL, turn_id TEXT NOT NULL, payload_cipher BLOB NOT NULL, token_estimate INTEGER NOT NULL, byte_count INTEGER NOT NULL, priority INTEGER NOT NULL, state TEXT NOT NULL, operation_id TEXT, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, FOREIGN KEY(session_id) REFERENCES openviking_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_openviking_outbox_session_id_source_message_key_capture_version ON openviking_outbox(session_id,source_message_key,capture_version)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_openviking_outbox_session_id_seq ON openviking_outbox(session_id,seq)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_openviking_outbox_connection_id_state_created_at ON openviking_outbox(connection_id,state,created_at)")
        db.execSQL("CREATE TABLE IF NOT EXISTS openviking_operations (id TEXT NOT NULL PRIMARY KEY, connection_id TEXT NOT NULL, session_id TEXT NOT NULL, kind TEXT NOT NULL, state TEXT NOT NULL, request_cipher BLOB NOT NULL, through_seq INTEGER NOT NULL, attempt_count INTEGER NOT NULL, next_attempt_at INTEGER NOT NULL, task_id TEXT, error_code TEXT, created_at INTEGER NOT NULL, FOREIGN KEY(session_id) REFERENCES openviking_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_openviking_operations_session_id ON openviking_operations(session_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_openviking_operations_connection_id_state ON openviking_operations(connection_id,state)")
        db.execSQL("CREATE TABLE IF NOT EXISTS openviking_injections (connection_id TEXT NOT NULL, local_scope_key TEXT NOT NULL, message_id TEXT NOT NULL, context_epoch INTEGER NOT NULL, outcome TEXT NOT NULL, payload_cipher BLOB, payload_version INTEGER NOT NULL, token_count INTEGER NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(connection_id,local_scope_key,message_id,context_epoch))")
        db.execSQL("CREATE TABLE IF NOT EXISTS openviking_cache (connection_id TEXT NOT NULL, cache_kind TEXT NOT NULL, cache_key TEXT NOT NULL, payload_cipher BLOB NOT NULL, expires_at INTEGER NOT NULL, PRIMARY KEY(connection_id,cache_kind,cache_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS openviking_sync_state (connection_id TEXT NOT NULL PRIMARY KEY, lease_owner TEXT, lease_until INTEGER NOT NULL, paused_reason TEXT, last_error TEXT, last_success_at INTEGER NOT NULL, evicted_count INTEGER NOT NULL)")
    }
}
