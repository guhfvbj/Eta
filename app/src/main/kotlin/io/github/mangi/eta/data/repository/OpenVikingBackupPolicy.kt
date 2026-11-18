package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.EtaDatabase

/** Export is a typed user-data allowlist, never a DB/preferences dump. These are intentionally
 * absent from EtaBackupDocument, streamed writers, validation and restore field dispatch. */
internal object OpenVikingBackupPolicy {
    val excludedNames = setOf("eta_openviking_secrets", "eta_openviking_credentials_v1", "eta_openviking_payload_v1",
        "openviking_settings_v1", "secretRevision", "openviking_sessions", "openviking_outbox", "openviking_operations",
        "openviking_injections", "openviking_cache", "openviking_sync_state")

    /** Must run INSIDE the restore transaction. A cancelled HTTP write may still be finishing:
     * refuse restore while its lease is live, rather than losing uncertain-delivery evidence. */
    suspend fun clearForRestore(db: EtaDatabase) {
        val dao = db.openVikingDao()
        val connections = dao.connectionIds()
        check(connections.none { (dao.syncState(it)?.leaseUntil ?: 0) > System.currentTimeMillis() }) { "OpenViking sync busy; retry restore after it stops" }
        for (connection in connections) {
            dao.clearSessions(connection)
            dao.clearInjections(connection)
            dao.clearCache(connection)
            dao.clearSyncState(connection)
        }
    }
}
