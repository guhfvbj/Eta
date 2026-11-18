package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

internal data class OpenVikingEvictableTurn(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "turn_id") val turnId: String,
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
)

@Dao
internal interface OpenVikingDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSession(row: OpenVikingSessionEntity): Long
    @Update suspend fun updateSession(row: OpenVikingSessionEntity)
    @Query("SELECT * FROM openviking_sessions WHERE id=:id") suspend fun session(id: String): OpenVikingSessionEntity?
    @Query("SELECT * FROM openviking_sessions WHERE connection_id=:connection AND local_scope_key=:scope AND context_epoch=:epoch LIMIT 1")
    suspend fun scopedSession(connection: String, scope: String, epoch: Int): OpenVikingSessionEntity?
    @Query("SELECT * FROM openviking_sessions WHERE connection_id=:connection ORDER BY id") suspend fun sessions(connection: String): List<OpenVikingSessionEntity>
    @Insert suspend fun insertOutbox(row: OpenVikingOutboxEntity)
    @Update suspend fun updateOutbox(row: OpenVikingOutboxEntity)
    @Query("SELECT * FROM openviking_outbox WHERE session_id=:session AND source_message_key=:source AND capture_version=:version LIMIT 1")
    suspend fun source(session: String, source: String, version: Int): OpenVikingOutboxEntity?
    @Query("SELECT * FROM openviking_outbox WHERE session_id=:session AND state='PENDING' ORDER BY seq LIMIT 20")
    suspend fun pending(session: String): List<OpenVikingOutboxEntity>
    @Query("SELECT * FROM openviking_outbox WHERE operation_id=:operation ORDER BY seq") suspend fun operationMessages(operation: String): List<OpenVikingOutboxEntity>
    @Query("SELECT COALESCE(SUM(byte_count),0) FROM openviking_outbox WHERE connection_id=:connection AND state!='ACKNOWLEDGED'")
    suspend fun queuedBytes(connection: String): Long
    @Query("SELECT COUNT(*) FROM openviking_outbox WHERE connection_id=:connection AND state!='ACKNOWLEDGED'")
    fun observeQueuedCount(connection: String): Flow<Int>
    @Query("SELECT session_id,turn_id,MAX(expires_at) AS expires_at FROM openviking_outbox WHERE connection_id=:connection GROUP BY session_id,turn_id HAVING MAX(priority)=0 AND MIN(CASE WHEN state='PENDING' THEN 1 ELSE 0 END)=1 ORDER BY MIN(created_at)")
    suspend fun evictableTurns(connection: String): List<OpenVikingEvictableTurn>
    @Query("DELETE FROM openviking_outbox WHERE session_id=:session AND turn_id=:turn AND state='PENDING' AND priority=0")
    suspend fun deleteAutoTurn(session: String, turn: String): Int
    @Query("SELECT COALESCE(SUM(token_estimate),0) FROM openviking_outbox WHERE session_id=:session AND state='ACKNOWLEDGED' AND seq>:after")
    suspend fun pendingTokens(session: String, after: Long): Long
    @Query("SELECT COUNT(DISTINCT turn_id) FROM openviking_outbox WHERE session_id=:session AND state='ACKNOWLEDGED' AND seq>:after")
    suspend fun pendingTurns(session: String, after: Long): Int
    @Query("SELECT COALESCE(MIN(created_at),0) FROM openviking_outbox WHERE session_id=:session AND state='ACKNOWLEDGED' AND seq>:after")
    suspend fun oldestUncommitted(session: String, after: Long): Long
    @Query("SELECT COUNT(*) FROM openviking_outbox WHERE session_id=:session AND state='ACKNOWLEDGED' AND seq>:after AND priority>0")
    suspend fun explicitPending(session: String, after: Long): Int
    @Insert suspend fun insertOperation(row: OpenVikingOperationEntity)
    @Update suspend fun updateOperation(row: OpenVikingOperationEntity)
    @Query("SELECT * FROM openviking_operations WHERE connection_id=:connection AND state IN ('SENDING','UNCERTAIN','POLLING','RETRY') ORDER BY created_at,id")
    suspend fun activeOperations(connection: String): List<OpenVikingOperationEntity>
    @Query("SELECT COUNT(*) FROM openviking_operations WHERE connection_id=:connection AND state='UNCERTAIN'")
    fun observeUncertainCount(connection: String): Flow<Int>
    @Query("SELECT COUNT(*) FROM openviking_operations WHERE connection_id=:connection AND state='POLLING'")
    fun observeExtractingCount(connection: String): Flow<Int>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSyncState(row: OpenVikingSyncStateEntity): Long
    @Update suspend fun updateSyncState(row: OpenVikingSyncStateEntity)
    @Query("SELECT * FROM openviking_sync_state WHERE connection_id=:connection") suspend fun syncState(connection: String): OpenVikingSyncStateEntity?
    @Query("SELECT * FROM openviking_sync_state WHERE connection_id=:connection") fun observeSyncState(connection: String): Flow<OpenVikingSyncStateEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putInjection(row: OpenVikingInjectionEntity)
    @Query("SELECT * FROM openviking_injections WHERE connection_id=:connection AND local_scope_key=:scope AND message_id=:message AND context_epoch=:epoch")
    suspend fun injection(connection: String, scope: String, message: String, epoch: Int): OpenVikingInjectionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCache(row: OpenVikingCacheEntity)
    @Query("SELECT * FROM openviking_cache WHERE connection_id=:connection AND cache_kind=:kind AND cache_key=:key")
    suspend fun cache(connection: String, kind: String, key: String): OpenVikingCacheEntity?
    @Query("DELETE FROM openviking_cache WHERE connection_id=:connection AND cache_kind=:kind AND cache_key=:key")
    suspend fun deleteCache(connection: String, kind: String, key: String)
    @Query("SELECT COUNT(*) FROM openviking_cache WHERE connection_id=:connection AND cache_kind=:kind")
    suspend fun cacheCount(connection: String, kind: String): Int
    @Query("DELETE FROM openviking_cache WHERE connection_id=:connection AND cache_kind='capture_draft' AND expires_at<=:now")
    suspend fun deleteExpiredCaptureDrafts(connection: String, now: Long)
    @Query("DELETE FROM openviking_sessions WHERE connection_id=:connection") suspend fun clearSessions(connection: String)
    @Query("DELETE FROM openviking_injections WHERE connection_id=:connection") suspend fun clearInjections(connection: String)
    @Query("DELETE FROM openviking_cache WHERE connection_id=:connection") suspend fun clearCache(connection: String)
    @Query("DELETE FROM openviking_sync_state WHERE connection_id=:connection") suspend fun clearSyncState(connection: String)
    @Query("SELECT connection_id FROM openviking_sessions UNION SELECT connection_id FROM openviking_sync_state UNION SELECT connection_id FROM openviking_injections UNION SELECT connection_id FROM openviking_cache")
    suspend fun connectionIds(): List<String>
}
