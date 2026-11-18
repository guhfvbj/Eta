package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.OpenVikingSettings
import io.github.mangi.eta.data.model.OpenVikingValidationException
import io.github.mangi.eta.data.model.OpenVikingLocalStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object OpenVikingSettingsRepository {
    private val saveMutex = Mutex()
    fun settingsFlow(): Flow<OpenVikingSettings> = SettingsDataStore.openVikingSettingsFlow()
    suspend fun settings(): OpenVikingSettings = SettingsDataStore.openVikingSettings()

    fun statusFlow(context: Context, connectionId: String?): Flow<OpenVikingLocalStatus> {
        if (connectionId == null) return flowOf(OpenVikingLocalStatus())
        val dao = EtaDatabase.get(context).openVikingDao()
        return combine(dao.observeQueuedCount(connectionId), dao.observeUncertainCount(connectionId),
            dao.observeSyncState(connectionId), dao.observeExtractingCount(connectionId)) { queued, uncertain, state, extracting ->
            OpenVikingLocalStatus(queued, uncertain, extracting, state?.lastError ?: state?.pausedReason,
                state?.lastSuccessAt ?: 0, state?.evictedCount ?: 0)
        }
    }

    /** null means keep the published credential. Old encrypted slots survive failed publication. */
    suspend fun save(context: Context, draft: OpenVikingSettings, apiKey: String? = null): OpenVikingSettings =
        withContext(Dispatchers.IO) {
            saveMutex.withLock {
                val old = settings()
                draft.normalized().validate(requireCredential = false)
                val secrets = OpenVikingSecretStore(context)
                val revision = if (apiKey == null) old.secretRevision else {
                    if (apiKey.isBlank() || apiKey.trim().length > 8192 || apiKey.trim().any { it.code !in 0x21..0x7e }) {
                        throw OpenVikingValidationException("apiKey")
                    }
                    secrets.stageCredential(apiKey.trim(), old.secretRevision)
                }
                val value = draft.copy(secretRevision = revision).normalized()
                value.validate()
                if (value.enabled && secrets.credential(revision) !is OpenVikingCredentialState.Available) {
                    throw OpenVikingSecretsUnavailable()
                }
                SettingsDataStore.setOpenVikingSettings(value)
                // Publication succeeded. Cleanup failure must not report that the save failed.
                runCatching { secrets.pruneCredentials(revision) }
                value
            }
        }

    suspend fun disable() = saveMutex.withLock {
        SettingsDataStore.setOpenVikingSettings(settings().copy(enabled = false))
    }

    suspend fun clearKey(context: Context) = withContext(Dispatchers.IO) {
        saveMutex.withLock {
            // Invalidate the reference first: no consumer can pair an old key with a new identity.
            SettingsDataStore.setOpenVikingSettings(settings().copy(enabled = false, secretRevision = ""))
            OpenVikingSecretStore(context).clearCredentials()
        }
    }

    /** Local-only reset. It neither cancels extraction on the server nor deletes remote memories. */
    suspend fun clearLocalState(context: Context, connectionId: String? = null) = withContext(Dispatchers.IO) {
        disable()
        io.github.mangi.eta.agent.openviking.OpenVikingSyncScheduler.cancel(context)
        val db = EtaDatabase.get(context)
        db.withTransaction {
            val dao = db.openVikingDao()
            val connections = connectionId?.let { listOf(it) } ?: dao.connectionIds()
            for (connection in connections) {
                check((dao.syncState(connection)?.leaseUntil ?: 0) <= System.currentTimeMillis()) { "OpenViking sync busy" }
            }
            for (connection in connections) {
                dao.clearSessions(connection)
                dao.clearInjections(connection)
                dao.clearCache(connection)
                dao.clearSyncState(connection)
            }
        }
    }
}
