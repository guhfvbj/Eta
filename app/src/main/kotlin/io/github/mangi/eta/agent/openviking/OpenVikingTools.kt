package io.github.mangi.eta.agent.openviking

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentOpenVikingToolCatalog
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.OpenVikingCredentialState
import io.github.mangi.eta.data.repository.OpenVikingSecretStore
import io.github.mangi.eta.data.repository.OpenVikingSettingsRepository
import io.github.mangi.eta.data.repository.OpenVikingSecretsUnavailable
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/** Independent of runtime recall. Every call rechecks the saved switches before any network I/O. */
internal class OpenVikingTools(private val context: Context, private val allowed: () -> Boolean = { true }) : AutoCloseable {
    private val cancellation = OpenVikingCancellationSource()
    private val syncRegistration = OpenVikingSyncScheduler.register(cancellation)
    @Volatile private var closed = false
    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        fun result(json: JSONObject) = AgentModelClient.ToolResult(json.toString(), sensitive = true)
        fun error(code: String) = result(JSONObject().put("ok", false).put("code", code).put("message", "OpenViking operation was not confirmed; inspect the code before retrying."))
        if (closed) return error("CANCELLED")
        if (!allowed()) return error("OPENVIKING_ROLEPLAY_BLOCKED")
        return try { runBlocking {
            if (!AgentMemoryRepository.isEnabled()) return@runBlocking error("MEMORY_DISABLED")
            val s = OpenVikingSettingsRepository.settings()
            if (call.name !in AgentOpenVikingToolCatalog.allowedNames(s)) return@runBlocking error("OPENVIKING_DISABLED_OR_NOT_ALLOWED")
            val secrets = OpenVikingSecretStore(context)
            val key = (secrets.credential(s.secretRevision) as? OpenVikingCredentialState.Available)?.apiKey ?: return@runBlocking error("CREDENTIAL_UNAVAILABLE")
            val api = OpenVikingHttpClient(s.baseUrl, key, s.actorPeer, s.foregroundDeadlineMs.toLong(), cancellation,
                mutationTimeoutMs = s.foregroundDeadlineMs.toLong())
            val args = JSONObject(call.argumentsJson.ifBlank { "{}" })
            fun required(name: String, max: Int): String {
                val value = args.opt(name) as? String ?: throw IllegalArgumentException()
                require(value.isNotBlank() && value.length <= max)
                return value
            }
            fun number(name: String, default: Int, min: Int, max: Int): Int {
                if (!args.has(name)) return default
                val n = args.get(name) as? Number ?: throw IllegalArgumentException()
                val value = n.toInt()
                require(n.toDouble() == value.toDouble() && value in min..max)
                return value
            }
            fun score(): Double = if (args.has("score_threshold")) {
                val value = (args.get("score_threshold") as? Number)?.toDouble() ?: throw IllegalArgumentException()
                require(value.isFinite() && value in 0.0..1.0); value
            } else s.scoreThreshold
            fun content(text: String, max: Int = 24000): AgentModelClient.ToolResult {
                val clean = OpenVikingTextSafety.clean(text, key)
                return result(JSONObject().put("ok", true).put("untrusted", true).put("content", clean.take(max)).put("truncated", clean.length > max))
            }
            when (call.name) {
                "ov_memory_recall" -> {
                    val request = OpenVikingRecallService.request(s, required("query", 16000), sessionId = null)
                        .copy(maxTokens = number("max_tokens", s.recallMaxTokens, 64, 32000), scoreThreshold = score(), dedupTurns = 0)
                    content(api.contextSearch(request).injectableText())
                }
                "ov_read" -> {
                    val uri = OpenVikingUriPolicy.validate(required("uri", 2048))
                    val level = when (args.optString("level", "L1")) { "L0" -> "abstract"; "L1" -> "overview"; "L2" -> "full"; else -> throw IllegalArgumentException() }
                    content(api.read(uri, level).let { if (it is JsonPrimitive) it.content else it.toString() }, number("max_chars", 8000, 128, 24000))
                }
                "ov_list" -> {
                    val uri = OpenVikingUriPolicy.validate(args.optString("uri", "viking://~/memories"))
                    val max = number("max_entries", 20, 1, 100)
                    val raw = api.list(uri, recursive = false).toString()
                    val parsed: Any = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
                    val entries = OpenVikingUriPolicy.entries(parsed)
                    val bounded = JSONArray((0 until minOf(max, entries.length())).map { entries.get(it) })
                    content(JSONObject().put("entries", bounded).put("truncated", entries.length() > max).toString())
                }
                "ov_search" -> {
                    val scope = args.optString("scope", "resources")
                    require(scope in setOf("resources", "skills"))
                    content(api.find(FindRequest(required("query", 16000), "viking://~/$scope",
                        limit = number("limit", 8, 1, 50), scoreThreshold = score())).toString())
                }
                "ov_memory_forget" -> {
                    val uri = OpenVikingUriPolicy.validate(required("uri", 2048))
                    // Anchor discovery to this key's own memory alias, then prove ownership from
                    // the authenticated listing. No caller-controlled cross-user parent traversal.
                    var directory = "viking://~/memories"
                    val visited = mutableSetOf<String>()
                    var target: String? = null
                    val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(s.foregroundDeadlineMs.toLong())
                    repeat(8) {
                        if (target != null || !visited.add(directory)) return@repeat
                        val remaining = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                        if (remaining <= 0) return@runBlocking error("TIMEOUT")
                        val listing = OpenVikingHttpClient(s.baseUrl, key, s.actorPeer, remaining, cancellation).list(directory, recursive = false).toString()
                        val parsed: Any = if (listing.startsWith("[")) JSONArray(listing) else JSONObject(listing)
                        target = runCatching { OpenVikingUriPolicy.verifiedForgetTarget(uri, s.actorPeer, parsed) }.getOrNull()
                        if (target == null) directory = OpenVikingUriPolicy.forgetChildDirectory(uri, parsed)
                            ?: return@runBlocking error("ACTOR_OWNERSHIP_UNVERIFIED")
                    }
                    if (target == null) return@runBlocking error("ACTOR_OWNERSHIP_UNVERIFIED")
                    api.delete(checkNotNull(target))
                    result(JSONObject().put("ok", true).put("status", "delete_accepted"))
                }
                "ov_memory_store" -> {
                    val clean = OpenVikingCaptureSanitizer.clean(required("content", 8000), key) ?: throw IllegalArgumentException()
                    require(clean.isNotBlank() && clean.toByteArray(Charsets.UTF_8).size <= 12000)
                    val wait = number("wait_ms", 0, 0, 5000)
                    val connection = checkNotNull(s.connectionId())
                    val id = call.id.ifBlank { UUID.randomUUID().toString() }
                    val outbox = OpenVikingOutboxRepository(EtaDatabase.get(context), secrets)
                    outbox.enqueue(connection, "eta-explicit-stores", "explicit:$id", 1,
                        AddMessageRequest("user", clean, peerId = s.actorPeer, turnId = "explicit:$id"),
                        (clean.length * 1.5).toInt(), OpenVikingPriority.EXPLICIT)
                    // Once enqueue succeeds, scheduling/transport failure must not look like a
                    // rejected store: another call id would enqueue the same fact a second time.
                    OpenVikingSyncScheduler.request(context, force = true)
                    val future = try { explicitWorker.submit {
                        runBlocking { if (!closed && allowed() && AgentMemoryRepository.isEnabled()) {
                            val current = OpenVikingSettingsRepository.settings()
                            if (current.connectionId() == connection && "ov_memory_store" in AgentOpenVikingToolCatalog.allowedNames(current))
                                OpenVikingSyncCoordinator(outbox).sync(current, api, force = true)
                        } }
                    } } catch (_: java.util.concurrent.RejectedExecutionException) { null }
                    var deferred = future == null
                    if (wait > 0 && future != null) try {
                        future.get(wait.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        deferred = true
                    } catch (_: Exception) { deferred = true }
                    result(JSONObject().put("ok", true).put("status", "queued")
                        .put("sync_deferred", deferred).put("extraction_confirmed", false))
                }
                else -> error("UNKNOWN_TOOL")
            }
        } } catch (failure: Exception) {
            error(when (failure) {
                is OpenVikingApiException -> if (failure.mayHaveReachedServer) "${failure.code.name}_UNCERTAIN" else failure.code.name
                is OpenVikingSecretsUnavailable -> "CREDENTIAL_UNAVAILABLE"
                is OpenVikingQueueFull -> "QUEUE_FULL"
                is IllegalArgumentException, is org.json.JSONException -> "INVALID_ARGUMENT"
                else -> "OPENVIKING_FAILED"
            })
        }
    }
    override fun close() { closed = true; cancellation.cancel(); syncRegistration.close() }
    companion object {
        private val explicitWorker = java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(8), { r -> Thread(r, "eta-ov-explicit").apply { isDaemon = true } })
    }
}
