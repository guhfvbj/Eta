package io.github.mangi.eta.ui.screens.memory

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.openviking.OpenVikingApiException
import io.github.mangi.eta.agent.openviking.OpenVikingCancellationSource
import io.github.mangi.eta.agent.openviking.OpenVikingHttpClient
import io.github.mangi.eta.agent.openviking.OpenVikingOutboxRepository
import io.github.mangi.eta.agent.openviking.OpenVikingSyncCoordinator
import io.github.mangi.eta.agent.openviking.OpenVikingSyncOutcome
import io.github.mangi.eta.agent.openviking.OpenVikingSyncScheduler
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.OpenVikingSettings
import io.github.mangi.eta.data.model.OpenVikingValidationException
import io.github.mangi.eta.data.repository.OpenVikingCredentialState
import io.github.mangi.eta.data.repository.OpenVikingSecretStore
import io.github.mangi.eta.data.repository.OpenVikingSecretsUnavailable
import io.github.mangi.eta.data.repository.OpenVikingSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class OpenVikingSettingsStore(context: Context, private val scope: CoroutineScope) {
    private val context = context.applicationContext
    private val requests = java.util.concurrent.CopyOnWriteArraySet<OpenVikingCancellationSource>()
    var state by mutableStateOf(OpenVikingSettingsUiState())
        private set

    init {
        scope.launch {
            SettingsDataStore.settingsFlow().collectLatest { state = state.copy(parentMemoryEnabled = it.memoryEnabled) }
        }
        scope.launch {
            try {
                OpenVikingSettingsRepository.settingsFlow().collectLatest { saved ->
                    if (!state.loaded || !state.dirty) {
                        val credential = withContext(Dispatchers.IO) { OpenVikingSecretStore(this@OpenVikingSettingsStore.context).credential(saved.secretRevision) }
                        state = state.copy(settings = saved,
                            apiKey = (credential as? OpenVikingCredentialState.Available)?.apiKey.orEmpty(),
                            credentialUnavailable = credential is OpenVikingCredentialState.Unavailable,
                            loaded = true, numericDrafts = numbers(saved))
                    }
                    val connection = runCatching { saved.connectionId() }.getOrNull()
                    OpenVikingSettingsRepository.statusFlow(this@OpenVikingSettingsStore.context, connection).collectLatest {
                        state = state.copy(status = it)
                    }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                state = state.copy(message = this@OpenVikingSettingsStore.context.getString(R.string.openviking_operation_failed), messageIsError = true)
            }
        }
    }

    fun edit(settings: OpenVikingSettings) { state = state.copy(settings = settings, dirty = true, message = null) }
    fun editKey(value: String) { state = state.copy(apiKey = value, keyDirty = true, dirty = true, message = null) }
    fun editNumber(field: String, value: String) {
        state = state.copy(numericDrafts = state.numericDrafts + (field to value), dirty = true, message = null)
    }

    fun setEnabled(enabled: Boolean) {
        edit(state.settings.copy(enabled = enabled))
        if (!enabled) {
            cancelRequests()
            OpenVikingSyncScheduler.cancel(context)
            action { OpenVikingSettingsRepository.disable(); success(R.string.openviking_disabled_saved) }
        }
    }

    private fun validatedDraft(): OpenVikingSettings {
        fun int(key: String): Int = state.numericDrafts[key]?.toIntOrNull() ?: throw OpenVikingValidationException(key)
        val score = state.numericDrafts["scoreThreshold"]?.toDoubleOrNull() ?: throw OpenVikingValidationException("scoreThreshold")
        return state.settings.copy(
            foregroundDeadlineMs = int("foregroundDeadlineMs"), recallMaxTokens = int("recallMaxTokens"),
            profileMaxTokens = int("profileMaxTokens"), scoreThreshold = score, dedupTurns = int("dedupTurns"),
            commitTokenThreshold = int("commitTokenThreshold"), commitTurnThreshold = int("commitTurnThreshold"),
            minCommitIntervalSeconds = int("minCommitIntervalSeconds"), idleCommitMinutes = int("idleCommitMinutes"),
        ).normalized().also { it.validate(requireCredential = false) }
    }

    fun save() = action {
        val draft = validatedDraft()
        val saved = OpenVikingSettingsRepository.save(context, draft, if (state.keyDirty) state.apiKey else null)
        OpenVikingSyncScheduler.cancel(context)
        OpenVikingSyncScheduler.request(context)
        state = state.copy(settings = saved, dirty = false, keyDirty = false, numericDrafts = numbers(saved), credentialUnavailable = false)
        success(R.string.openviking_saved)
    }

    fun testConnection() = action {
        val draft = validatedDraft()
        val key = state.apiKey.trim()
        if (key.isBlank()) throw OpenVikingValidationException("apiKey")
        withClient(draft, key) { it.testConnection() }
        success(R.string.openviking_connection_ok)
    }

    fun syncNow() = action {
        if (state.dirty) { success(R.string.openviking_save_first, error = true); return@action }
        if (!state.parentMemoryEnabled) { success(R.string.openviking_status_parent_disabled, error = true); return@action }
        val saved = OpenVikingSettingsRepository.settings()
        if (!saved.enabled) { success(R.string.openviking_status_disabled, error = true); return@action }
        saved.validate()
        val credential = withContext(Dispatchers.IO) { OpenVikingSecretStore(context).credential(saved.secretRevision) }
        val key = (credential as? OpenVikingCredentialState.Available)?.apiKey ?: throw OpenVikingSecretsUnavailable()
        val coordinator = OpenVikingSyncCoordinator(OpenVikingOutboxRepository(EtaDatabase.get(context), OpenVikingSecretStore(context)))
        coordinator.resumeAfterConfigurationCheck(checkNotNull(saved.connectionId()))
        val outcome = withClient(saved, key) { coordinator.sync(saved, it, force = true) }
        OpenVikingSyncScheduler.request(context)
        success(when (outcome) {
            OpenVikingSyncOutcome.UNCERTAIN -> R.string.openviking_uncertain_help
            OpenVikingSyncOutcome.BUSY, OpenVikingSyncOutcome.WAITING -> R.string.openviking_sync_waiting
            OpenVikingSyncOutcome.PAUSED -> R.string.openviking_sync_paused
            else -> R.string.openviking_sync_checked
        })
    }

    fun refreshStatus() = action {
        // Room status is live. Sync performs only due task polls, never forcibly replays uncertain writes.
        if (!state.dirty && state.settings.enabled && state.parentMemoryEnabled) {
            val saved = OpenVikingSettingsRepository.settings()
            val credential = withContext(Dispatchers.IO) { OpenVikingSecretStore(context).credential(saved.secretRevision) }
            if (credential is OpenVikingCredentialState.Available) {
                val coordinator = OpenVikingSyncCoordinator(OpenVikingOutboxRepository(EtaDatabase.get(context), OpenVikingSecretStore(context)))
                withClient(saved, credential.apiKey) { coordinator.sync(saved, it, pollOnly = true) }
            }
        }
        success(R.string.openviking_status_refreshed)
    }

    fun clearKey() = action {
        cancelRequests()
        OpenVikingSyncScheduler.cancel(context)
        OpenVikingSettingsRepository.clearKey(context)
        val saved = OpenVikingSettingsRepository.settings()
        state = state.copy(apiKey = "", keyDirty = false, dirty = false, credentialUnavailable = false,
            settings = saved, numericDrafts = numbers(saved))
        success(R.string.openviking_key_cleared)
    }

    fun clearLocalCache() = action {
        cancelRequests()
        OpenVikingSyncScheduler.cancel(context)
        OpenVikingSettingsRepository.clearLocalState(context)
        val saved = OpenVikingSettingsRepository.settings()
        val credential = withContext(Dispatchers.IO) { OpenVikingSecretStore(context).credential(saved.secretRevision) }
        state = state.copy(settings = saved, dirty = false, keyDirty = false,
            numericDrafts = numbers(saved), credentialUnavailable = credential is OpenVikingCredentialState.Unavailable,
            apiKey = (credential as? OpenVikingCredentialState.Available)?.apiKey.orEmpty())
        success(R.string.openviking_local_cleared)
    }

    private fun action(block: suspend () -> Unit) {
        if (!state.loaded || state.busy) return
        state = state.copy(busy = true, message = null)
        scope.launch {
            try { block() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val message = when (failure) {
                    is OpenVikingValidationException -> {
                        val resource = openVikingNumericFields.firstOrNull { it.first == failure.field }?.second ?: when (failure.field) {
                            "baseUrl" -> R.string.openviking_base_url
                            "actorPeer" -> R.string.openviking_actor_peer
                            "apiKey" -> R.string.openviking_api_key
                            else -> R.string.openviking_advanced
                        }
                        context.getString(R.string.openviking_invalid_field, context.getString(resource))
                    }
                    is OpenVikingApiException -> openVikingErrorText(context, failure.code.name)
                    is OpenVikingSecretsUnavailable -> context.getString(R.string.openviking_credentials_unavailable)
                    else -> context.getString(R.string.openviking_operation_failed)
                }
                state = state.copy(message = message, messageIsError = true)
            } finally { state = state.copy(busy = false) }
        }
    }

    private suspend fun <T> withClient(settings: OpenVikingSettings, key: String, block: suspend (OpenVikingHttpClient) -> T): T {
        val source = OpenVikingCancellationSource()
        requests.add(source)
        return try {
            withContext(Dispatchers.IO) {
                block(OpenVikingHttpClient(settings.baseUrl, key, settings.actorPeer,
                    settings.foregroundDeadlineMs.toLong(), source))
            }
        } finally { requests.remove(source) }
    }

    private fun success(resource: Int, error: Boolean = false) {
        state = state.copy(message = context.getString(resource), messageIsError = error)
    }

    fun cancelRequests() { requests.forEach { it.cancel() } }
    fun close() { cancelRequests(); state = state.copy(apiKey = "") }

    companion object {
        private fun numbers(s: OpenVikingSettings): Map<String, String> = mapOf(
            "foregroundDeadlineMs" to s.foregroundDeadlineMs.toString(), "recallMaxTokens" to s.recallMaxTokens.toString(),
            "profileMaxTokens" to s.profileMaxTokens.toString(), "scoreThreshold" to s.scoreThreshold.toString(),
            "dedupTurns" to s.dedupTurns.toString(), "commitTokenThreshold" to s.commitTokenThreshold.toString(),
            "commitTurnThreshold" to s.commitTurnThreshold.toString(), "minCommitIntervalSeconds" to s.minCommitIntervalSeconds.toString(),
            "idleCommitMinutes" to s.idleCommitMinutes.toString(),
        )
    }
}
