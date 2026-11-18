package io.github.mangi.eta.data.model

import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal enum class OpenVikingPeerScope { @SerialName("actor") ACTOR, @SerialName("all") ALL }
@Serializable
internal enum class OpenVikingQueryExpansion { @SerialName("off") OFF, @SerialName("auto") AUTO }
@Serializable
internal enum class OpenVikingRewriteMode { @SerialName("false") OFF, @SerialName("auto") AUTO, @SerialName("true") ON }
@Serializable
internal enum class OpenVikingPurpose { @SerialName("chat") CHAT, @SerialName("coding") CODING }

/** Contains no API key or digest of an API key. secretRevision names an encrypted credential slot. */
@Serializable
internal data class OpenVikingSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val actorPeer: String = "eta",
    val secretRevision: String = "",
    val peerScope: OpenVikingPeerScope = OpenVikingPeerScope.ACTOR,
    val queryExpansion: OpenVikingQueryExpansion = OpenVikingQueryExpansion.AUTO,
    val rewrite: OpenVikingRewriteMode = OpenVikingRewriteMode.AUTO,
    val purpose: OpenVikingPurpose = OpenVikingPurpose.CHAT,
    val autoRecall: Boolean = true,
    val profileInjection: Boolean = true,
    val autoCapture: Boolean = true,
    val captureAssistantReplies: Boolean = true,
    val toolsEnabled: Boolean = true,
    val allowExplicitStore: Boolean = true,
    val allowForget: Boolean = false,
    val foregroundDeadlineMs: Int = 3000,
    val recallMaxTokens: Int = 1600,
    val profileMaxTokens: Int = 600,
    val scoreThreshold: Double = 0.35,
    val dedupTurns: Int = 5,
    val commitTokenThreshold: Int = 4000,
    val commitTurnThreshold: Int = 8,
    val minCommitIntervalSeconds: Int = 60,
    val idleCommitMinutes: Int = 5,
) {
    fun normalized(): OpenVikingSettings = copy(
        baseUrl = if (baseUrl.isBlank()) "" else normalizeBaseUrl(baseUrl),
        actorPeer = actorPeer.trim(),
    )

    fun validate(requireCredential: Boolean = enabled) {
        if (enabled || baseUrl.isNotBlank()) normalizeBaseUrl(baseUrl)
        checkField(actorPeer.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")), "actorPeer")
        checkField(!requireCredential || secretRevision.isNotBlank(), "apiKey")
        checkField(foregroundDeadlineMs in 500..15000, "foregroundDeadlineMs")
        checkField(recallMaxTokens in 64..32000, "recallMaxTokens")
        checkField(profileMaxTokens in 0..4000, "profileMaxTokens")
        checkField(scoreThreshold.isFinite() && scoreThreshold in 0.0..1.0, "scoreThreshold")
        checkField(dedupTurns in 0..100, "dedupTurns")
        checkField(commitTokenThreshold in 256..64000, "commitTokenThreshold")
        checkField(commitTurnThreshold in 1..100, "commitTurnThreshold")
        checkField(minCommitIntervalSeconds in 0..3600, "minCommitIntervalSeconds")
        checkField(idleCommitMinutes in 1..1440, "idleCommitMinutes")
    }

    fun connectionId(): String? = if (baseUrl.isBlank() || secretRevision.isBlank()) null else
        openVikingHash("v1\n${normalizeBaseUrl(baseUrl)}\n${actorPeer.trim()}\n$secretRevision")

    companion object {
        fun normalizeBaseUrl(raw: String): String {
            val uri = try { URI(raw.trim()) } catch (_: Exception) { throw OpenVikingValidationException("baseUrl") }
            checkField(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank(), "baseUrl")
            checkField(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null, "baseUrl")
            checkField(uri.port == -1 || uri.port in 1..65535, "baseUrl")
            val path = uri.path.orEmpty().trimEnd('/')
            checkField(path.split('/').none { it == "." || it == ".." } && !path.contains('\\'), "baseUrl")
            return URI("https", null, uri.host.lowercase(), if (uri.port == 443) -1 else uri.port,
                path, null, null).toASCIIString()
        }

        private fun checkField(valid: Boolean, field: String) {
            if (!valid) throw OpenVikingValidationException(field)
        }
    }
}

internal class OpenVikingValidationException(val field: String) : IllegalArgumentException("Invalid OpenViking field: $field")

internal data class OpenVikingLocalStatus(
    val queued: Int = 0,
    val uncertain: Int = 0,
    val extracting: Int = 0,
    val lastError: String? = null,
    val lastSuccessAt: Long = 0,
    val evictedCount: Long = 0,
)

internal fun openVikingHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
