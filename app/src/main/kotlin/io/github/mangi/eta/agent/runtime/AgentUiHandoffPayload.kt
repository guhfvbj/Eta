package io.github.mangi.eta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

internal data class AgentUiHandoffPayload(
    val conversationId: String,
    val promptSupplement: Supplement? = null,
    val supplements: List<Supplement> = emptyList(),
    val origin: String = ORIGIN_USER,
) {
    val lastSupplementIndex: Int
        get() = (listOfNotNull(promptSupplement?.index) + supplements.map { it.index }).maxOrNull() ?: 0

    fun promptMessageId(runId: String): String =
        promptSupplement?.let { "user-$runId-supplement-${it.index}" } ?: "user-$runId"

    data class Supplement(
        val index: Int,
        val text: String,
        val createdAt: Long,
        val source: String = AgentRuntimeWire.SUPPLEMENT_SOURCE_USER,
        val jobId: String = "",
    )

    fun toJson(): String =
        JSONObject()
            .put("type", TYPE)
            .put("version", VERSION)
            .put("conversationId", conversationId)
            .put("origin", origin)
            .also { json ->
                promptSupplement?.let { json.put("promptSupplement", it.toJson()) }
            }
            .put(
                "supplements",
                JSONArray().also { array ->
                    supplements.forEach { supplement ->
                        array.put(
                            supplement.toJson()
                        )
                    }
                }
            )
            .toString()

    companion object {
        private const val TYPE = "agent_ui_handoff"
        private const val VERSION = 3
        const val ORIGIN_USER = "user"
        const val ORIGIN_JOB_WAKEUP = "job_wakeup"

        fun from(raw: String): AgentUiHandoffPayload {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("{")) {
                return AgentUiHandoffPayload(conversationId = trimmed)
            }
            return runCatching {
                val json = JSONObject(trimmed)
                if (json.optString("type") != TYPE) {
                    return@runCatching AgentUiHandoffPayload(conversationId = trimmed)
                }
                val supplementsJson = json.optJSONArray("supplements") ?: JSONArray()
                AgentUiHandoffPayload(
                    conversationId = json.optString("conversationId"),
                    origin = json.optString("origin", ORIGIN_USER).ifBlank { ORIGIN_USER },
                    promptSupplement = json.optJSONObject("promptSupplement")
                        ?.toSupplement(defaultIndex = 1),
                    supplements = (0 until supplementsJson.length()).mapNotNull { index ->
                        supplementsJson.optJSONObject(index)?.toSupplement(index + 1)
                    }
                )
            }.getOrDefault(AgentUiHandoffPayload(conversationId = trimmed))
        }

        private fun JSONObject.toSupplement(defaultIndex: Int): Supplement? {
            val text = optString("text").trim()
            if (text.isBlank()) return null
            return Supplement(
                index = optInt("index", defaultIndex),
                text = text,
                createdAt = optLong("createdAt"),
                source = optString("source", AgentRuntimeWire.SUPPLEMENT_SOURCE_USER).ifBlank { AgentRuntimeWire.SUPPLEMENT_SOURCE_USER },
                jobId = optString("jobId", ""),
            )
        }
    }

    private fun Supplement.toJson(): JSONObject =
        JSONObject()
            .put("index", index)
            .put("text", text)
            .put("createdAt", createdAt)
            .put("source", source)
            .also { if (jobId.isNotBlank()) it.put("jobId", jobId) }
}
