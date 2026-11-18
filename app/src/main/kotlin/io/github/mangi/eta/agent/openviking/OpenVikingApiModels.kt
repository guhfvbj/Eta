package io.github.mangi.eta.agent.openviking

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Context mode intentionally cannot represent target_uri or read_content. */
@Serializable
internal data class ContextSearchRequest(
    val query: String,
    val mode: String = "context",
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("query_expansion") val queryExpansion: String = "auto",
    @SerialName("max_tokens") val maxTokens: Int = 1600,
    val quotas: Map<String, Int>? = null,
    val purpose: String = "chat",
    val detail: String = "auto",
    @SerialName("score_threshold") val scoreThreshold: Double = 0.35,
    @SerialName("dedup_turns") val dedupTurns: Int = 5,
    @SerialName("exclude_uris") val excludeUris: List<String> = emptyList(),
    @SerialName("peer_scope") val peerScope: String = "actor",
    @SerialName("events_time_decay_protection") val eventsTimeDecayProtection: String = "7d",
    val rewrite: JsonPrimitive = JsonPrimitive("auto"),
    @SerialName("rewrite_max_bullets") val rewriteMaxBullets: Int? = null,
)

@Serializable
internal data class ContextSearchStats(
    @SerialName("used_tokens") val usedTokens: Int = 0,
    val rewrite: String? = null,
    @SerialName("tier_counts") val tierCounts: JsonObject? = null,
)

@Serializable
internal data class ContextSearchResponse(
    val rendered: String = "",
    val entries: List<JsonObject> = emptyList(),
    val digest: String = "",
    val stats: ContextSearchStats = ContextSearchStats(),
) {
    fun injectableText(): String = if (stats.rewrite == "no_relevant") "" else digest.ifBlank { rendered }
}

@Serializable
internal data class FindRequest(
    val query: String,
    @SerialName("target_uri") val targetUri: String,
    @SerialName("context_type") val contextType: String? = null,
    val limit: Int = 8,
    @SerialName("score_threshold") val scoreThreshold: Double = 0.35,
    @SerialName("read_content") val readContent: Boolean = false,
    val since: String? = null,
    val until: String? = null,
    val tags: List<String>? = null,
)

@Serializable
internal data class WriteContentRequest(
    val uri: String,
    val content: String,
    val mode: String,
    val wait: Boolean = false,
    val tags: List<String>? = null,
    @SerialName("edit_source") val editSource: String? = null,
)

@Serializable
internal data class CreateSessionRequest(@SerialName("session_id") val sessionId: String)

@Serializable
internal data class AddMessageRequest(
    val role: String,
    val content: String,
    @SerialName("peer_id") val peerId: String? = null,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("message_kind") val messageKind: String? = null,
)

@Serializable
internal data class BatchAddMessageRequest(val messages: List<AddMessageRequest>)

@Serializable
internal data class CommitRequest(
    @SerialName("keep_recent_count") val keepRecentCount: Int = 0,
    @SerialName("retention_mode") val retentionMode: String? = null,
    @SerialName("keep_recent_turn_count") val keepRecentTurnCount: Int? = null,
    @SerialName("retained_message_token_budget") val retainedMessageTokenBudget: Int? = null,
    @SerialName("session_window_context") val sessionWindowContext: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    // Leave the server's extraction-quality policy intact unless a caller explicitly overrides it.
    @SerialName("enable_working_memory") val enableWorkingMemory: Boolean? = null,
    @SerialName("reset_context") val resetContext: Boolean = false,
)

@Serializable
internal data class CommitResponse(@SerialName("task_id") val taskId: String)

@Serializable
internal data class TaskStatusResponse(
    val status: String,
    @SerialName("task_id") val taskId: String? = null,
    val result: JsonElement? = null,
) {
    val succeeded: Boolean get() = status.lowercase() in setOf("completed", "success", "succeeded", "done")
    val failed: Boolean get() = status.lowercase() in setOf("failed", "error", "cancelled", "canceled")
}

internal enum class OpenVikingErrorCode {
    AUTH_FAILED, TLS_ERROR, TIMEOUT, NETWORK_ERROR, RATE_LIMITED, PROTOCOL_ERROR, CANCELLED,
}

/** Never contains response bodies, authentication values or exception messages from the transport. */
internal class OpenVikingApiException(
    val code: OpenVikingErrorCode,
    val httpStatus: Int? = null,
    val mayHaveReachedServer: Boolean = false,
) : Exception("OpenViking ${code.name}${httpStatus?.let { " ($it)" }.orEmpty()}")

internal fun interface OpenVikingCancellation {
    fun register(cancel: () -> Unit): AutoCloseable
}

internal class OpenVikingCancellationSource : OpenVikingCancellation {
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    override fun register(cancel: () -> Unit): AutoCloseable {
        listeners.add(cancel)
        if (cancelled.get()) cancel()
        return AutoCloseable { listeners.remove(cancel) }
    }
    fun cancel() {
        cancelled.set(true)
        listeners.forEach { it() }
    }
}

internal interface OpenVikingSyncApi {
    fun createSession(request: CreateSessionRequest)
    fun addMessages(sessionId: String, request: BatchAddMessageRequest)
    fun commit(sessionId: String, request: CommitRequest = CommitRequest()): CommitResponse
    fun task(taskId: String): TaskStatusResponse
}
