package io.github.mangi.eta.ui.screens.memory

import android.content.Context
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.OpenVikingLocalStatus
import io.github.mangi.eta.data.model.OpenVikingSettings

/** API key lives only in this remembered store, never in rememberSaveable / navigation arguments. */
internal data class OpenVikingSettingsUiState(
    val settings: OpenVikingSettings = OpenVikingSettings(),
    val apiKey: String = "",
    val numericDrafts: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val dirty: Boolean = false,
    val keyDirty: Boolean = false,
    val credentialUnavailable: Boolean = false,
    val parentMemoryEnabled: Boolean = true,
    val status: OpenVikingLocalStatus = OpenVikingLocalStatus(),
    val message: String? = null,
    val messageIsError: Boolean = false,
) {
    override fun toString(): String = "OpenVikingSettingsUiState(loaded=$loaded, busy=$busy, apiKey=[redacted])"
}

internal fun openVikingSummaryText(
    context: Context, settings: OpenVikingSettings, status: OpenVikingLocalStatus,
    credentialAvailable: Boolean, parentEnabled: Boolean,
): String = when {
    !settings.enabled -> context.getString(R.string.openviking_status_disabled)
    !parentEnabled -> context.getString(R.string.openviking_status_parent_disabled)
    !credentialAvailable || runCatching { settings.validate(); settings.connectionId() }.getOrNull() == null ->
        context.getString(R.string.openviking_status_unconfigured)
    status.uncertain > 0 -> context.getString(R.string.openviking_status_uncertain, status.uncertain)
    status.lastError != null -> context.getString(R.string.openviking_status_error, openVikingErrorText(context, status.lastError))
    status.extracting > 0 -> context.getString(R.string.openviking_status_extracting)
    status.queued > 0 -> context.getString(R.string.openviking_status_queued, status.queued)
    status.lastSuccessAt > 0 -> context.getString(R.string.openviking_status_ready)
    else -> context.getString(R.string.openviking_status_configured)
}

internal fun openVikingErrorText(context: Context, code: String): String = context.getString(when (code) {
    "AUTH_FAILED" -> R.string.openviking_error_auth
    "TLS_ERROR" -> R.string.openviking_error_tls
    "TIMEOUT" -> R.string.openviking_error_timeout
    "NETWORK_ERROR" -> R.string.openviking_error_network
    "RATE_LIMITED" -> R.string.openviking_error_rate_limit
    "CANCELLED" -> R.string.openviking_error_cancelled
    "EXTRACTION_FAILED" -> R.string.openviking_error_extraction
    "CREDENTIAL_UNAVAILABLE", "ENCRYPTION_UNAVAILABLE" -> R.string.openviking_credentials_unavailable
    else -> R.string.openviking_error_protocol
})
