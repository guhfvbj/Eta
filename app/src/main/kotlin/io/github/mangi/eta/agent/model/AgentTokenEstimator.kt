package io.github.mangi.eta.agent.model

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject

/** 本地估算偏保守下限，不代表计费用量；超限由 overflow 恢复兜底。图片仍预留固定预算。 */
internal object AgentTokenEstimator {
    const val VERSION = 1

    fun text(value: String, legacy: Boolean = false): Int {
        var sixths = 0L
        var index = 0
        while (index < value.length) {
            val point = value.codePointAt(index)
            sixths += when {
                point > 0xffff -> 16 // four UTF-8 bytes
                point > 0x7ff -> if (legacy) 12 else 6 // 旧权重仅用于迁移既有 usage 前缀锚点
                point > 0x7f -> 8
                point.toChar().isLetterOrDigit() || point.toChar().isWhitespace() -> 2
                else -> 4 // JSON, source code and punctuation
            }
            index += Character.charCount(point)
        }
        return bounded((sixths + 5) / 6)
    }

    fun json(value: Any?, legacy: Boolean = false): Int = when (value) {
        null, JSONObject.NULL -> 0
        is JSONArray -> bounded((0 until value.length()).sumOf { json(value.opt(it), legacy).toLong() } + 2)
        is JSONObject -> {
            if (value.optString("type") in setOf("image_url", "input_image", "image")) 8_192
            else bounded(value.keys().asSequence().filterNot { it.startsWith("_eta_") }.sumOf {
                text(it, legacy).toLong() + json(value.opt(it), legacy) + 2
            } + 2)
        }
        else -> text(value.toString(), legacy)
    }

    fun messages(messages: JSONArray, start: Int = 0, end: Int = messages.length(), legacy: Boolean = false): Int =
        bounded((start until end).sumOf { json(messages.opt(it), legacy).toLong() + 12 })

    fun messages(messages: List<AgentModelClient.ConversationMessage>, legacy: Boolean = false): Int =
        messages(JSONArray(messages.map(AgentConversationCodec::toJsonObject)), legacy = legacy)

    fun fingerprint(messages: List<AgentModelClient.ConversationMessage>): String =
        MessageDigest.getInstance("SHA-256")
            .digest(AgentConversationCodec.encodeTranscriptForStorage(messages).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun modelKey(config: AgentModelClient.ModelConfig): String =
        MessageDigest.getInstance("SHA-256").digest(
            "${config.providerType}|${config.baseUrl}|${config.model}|${config.knownContextWindow}".toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }

    fun bounded(value: Long): Int = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}

/** Tokens include the request prefix; the fingerprint covers only sanitized durable history. */
@Serializable
internal data class AgentContextTokenBaseline(
    val lastContextTokens: Int,
    val messageCount: Int,
    val fingerprint: String,
    val prefixTokens: Int,
    val modelKey: String,
    val estimateScale: Double = 1.0,
    val compactionNoProgressCount: Int = 0,
    val compactionCooldownRemaining: Int = 0,
    val estimatorVersion: Int = 0,
) {
    val boundedEstimateScale: Double get() = if (estimateScale.isFinite()) estimateScale.coerceIn(0.1, 2.0) else 1.0

    fun matches(history: List<AgentModelClient.ConversationMessage>, config: AgentModelClient.ModelConfig): Boolean =
        lastContextTokens >= 0 && prefixTokens >= 0 && messageCount in 0..history.size &&
            modelKey == AgentTokenEstimator.modelKey(config) &&
            fingerprint == AgentTokenEstimator.fingerprint(history.take(messageCount))
}
