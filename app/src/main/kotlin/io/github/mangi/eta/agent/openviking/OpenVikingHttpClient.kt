package io.github.mangi.eta.agent.openviking

import io.github.mangi.eta.data.model.OpenVikingSettings
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A bounded, non-retrying 0.5.4 client. No shared model-stream timeouts or arbitrary header overrides. */
internal class OpenVikingHttpClient(
    baseUrl: String,
    private val apiKey: String,
    private val actorPeer: String = "eta",
    private val timeoutMs: Long = 3000,
    private val cancellation: OpenVikingCancellation? = null,
    private val maxResponseBytes: Int = 512 * 1024,
    private val mutationTimeoutMs: Long = 30000,
    transport: OkHttpClient = sharedTransport,
    // Test fixture injection only; production configuration always requires HTTPS.
    allowLoopbackHttpForTests: Boolean = false,
) : OpenVikingSyncApi {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val base = if (allowLoopbackHttpForTests && baseUrl.toHttpUrl().let {
        it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost", "[::1]", "::1")
    }) baseUrl.trimEnd('/').toHttpUrl() else OpenVikingSettings.normalizeBaseUrl(baseUrl).toHttpUrl()
    private val client = transport.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(3000, TimeUnit.MILLISECONDS).readTimeout(15000, TimeUnit.MILLISECONDS)
        .writeTimeout(15000, TimeUnit.MILLISECONDS).build()

    init {
        require(apiKey.isNotBlank() && apiKey.length <= 8192 && apiKey.all { it.code in 0x21..0x7e })
        require(actorPeer.isEmpty() || actorPeer.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        require(timeoutMs in 1..60000 && mutationTimeoutMs in 1..120000 && maxResponseBytes in 64..(2 * 1024 * 1024))
    }

    fun health(): JsonObject = healthObject(raw("GET", listOf("health"), envelope = false))
    fun status(): JsonElement = raw("GET", listOf("api", "v1", "system", "status"))

    /** Includes both probes in one deadline. Tests never create a session or memory. */
    fun testConnection(): JsonElement {
        val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        healthObject(raw("GET", listOf("health"), envelope = false, deadlineMs = remaining(end)))
        return raw("GET", listOf("api", "v1", "system", "status"), deadlineMs = remaining(end)).also {
            if (it !is JsonObject) throw OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR)
        }
    }

    fun contextSearch(request: ContextSearchRequest): ContextSearchResponse {
        require(request.mode == "context" && request.query.isNotBlank() && request.query.length <= 16000)
        require(request.maxTokens in 64..32000 && request.dedupTurns in 0..100)
        require(request.scoreThreshold.isFinite() && request.scoreThreshold in 0.0..1.0)
        require(request.queryExpansion in setOf("auto", "off") && request.peerScope in setOf("actor", "all"))
        require(request.purpose in setOf("chat", "coding") && request.detail in setOf("auto", "abstract", "overview", "full"))
        require(request.rewrite.toString() in setOf("true", "false", "\"auto\""))
        require(request.rewriteMaxBullets == null || request.rewriteMaxBullets in 1..20)
        return decode(raw("POST", listOf("api", "v1", "search", "search"), json.encodeToString(request)))
    }

    fun find(request: FindRequest): JsonElement {
        require(request.query.isNotBlank() && request.limit in 1..100)
        return raw("POST", listOf("api", "v1", "search", "find"), json.encodeToString(request))
    }

    fun read(uri: String, level: String = "overview"): JsonElement {
        val endpoint = when (level) { "abstract" -> "abstract"; "overview" -> "overview"; "full" -> "read"; else -> error("Invalid content level") }
        return raw("GET", listOf("api", "v1", "content", endpoint), query = mapOf("uri" to uri))
    }

    fun write(request: WriteContentRequest): JsonElement = raw("POST", listOf("api", "v1", "content", "write"), json.encodeToString(request), mutation = true)
    fun list(uri: String, recursive: Boolean = false): JsonElement = raw("GET", listOf("api", "v1", "fs", "ls"),
        query = mapOf("uri" to uri, "output" to "original", "recursive" to recursive.toString()))
    fun delete(uri: String): JsonElement = raw("DELETE", listOf("api", "v1", "fs"),
        query = mapOf("uri" to uri, "recursive" to "false", "wait" to "false"), mutation = true)

    override fun createSession(request: CreateSessionRequest) {
        raw("POST", listOf("api", "v1", "sessions"), json.encodeToString(request), mutation = true)
    }

    override fun addMessages(sessionId: String, request: BatchAddMessageRequest) {
        require(request.messages.size in 1..20 && request.messages.all { it.role in setOf("user", "assistant") })
        val body = json.encodeToString(request)
        require(body.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        raw("POST", listOf("api", "v1", "sessions", sessionId, "messages", "batch"), body, mutation = true)
    }

    override fun commit(sessionId: String, request: CommitRequest): CommitResponse {
        val response: CommitResponse = decode(raw("POST", listOf("api", "v1", "sessions", sessionId, "commit"), json.encodeToString(request), mutation = true), mutation = true)
        if (response.taskId.isBlank()) throw OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR, mayHaveReachedServer = true)
        return response
    }

    override fun task(taskId: String): TaskStatusResponse = decode(raw("GET", listOf("api", "v1", "tasks", taskId)))

    private inline fun <reified T> decode(element: JsonElement, mutation: Boolean = false): T = try {
        json.decodeFromString<T>(element.toString())
    } catch (_: Exception) { throw OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR, mayHaveReachedServer = mutation) }

    private fun remaining(end: Long): Long {
        val ms = TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())
        if (ms <= 0) throw OpenVikingApiException(OpenVikingErrorCode.TIMEOUT)
        return ms
    }

    private fun raw(
        method: String, path: List<String>, body: String? = null,
        query: Map<String, String> = emptyMap(), envelope: Boolean = true,
        mutation: Boolean = false, deadlineMs: Long = if (mutation) mutationTimeoutMs else timeoutMs,
    ): JsonElement {
        val startedSending = AtomicBoolean(false)
        val userCancelled = AtomicBoolean(false)
        val url = base.newBuilder().apply {
            path.forEach { require(it.isNotBlank()); addPathSegment(it) }
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json").apply {
                if (actorPeer.isNotBlank()) header("X-OpenViking-Actor-Peer", actorPeer)
            }.method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val call = client.newBuilder().eventListener(object : EventListener() {
            override fun requestHeadersStart(call: Call) { startedSending.set(true) }
        }).build().newCall(request)
        call.timeout().timeout(deadlineMs, TimeUnit.MILLISECONDS)
        val binding = cancellation?.register { userCancelled.set(true); call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val code = when (response.code) {
                        401, 403 -> OpenVikingErrorCode.AUTH_FAILED
                        429 -> OpenVikingErrorCode.RATE_LIMITED
                        else -> OpenVikingErrorCode.PROTOCOL_ERROR
                    }
                    throw OpenVikingApiException(code, response.code,
                        mutation && response.code !in setOf(400, 401, 403, 404, 405, 413, 422, 429))
                }
                val responseBody = response.body
                if (responseBody.contentLength() > maxResponseBytes) throw OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR, mayHaveReachedServer = mutation)
                val source = responseBody.source()
                if (source.request(maxResponseBytes.toLong() + 1)) throw OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR, mayHaveReachedServer = mutation)
                val parsed = try { json.parseToJsonElement(source.readUtf8()) } catch (_: Exception) {
                    throw OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR, mayHaveReachedServer = mutation)
                }
                if (!envelope) return parsed
                val obj = parsed as? JsonObject
                if (obj == null || (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "ok" || obj["result"] == null) {
                    throw OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR, mayHaveReachedServer = mutation)
                }
                return obj.getValue("result")
            }
        } catch (failure: OpenVikingApiException) { throw failure
        } catch (_: SSLException) {
            throw OpenVikingApiException(if (userCancelled.get() || Thread.currentThread().isInterrupted) OpenVikingErrorCode.CANCELLED else OpenVikingErrorCode.TLS_ERROR,
                mayHaveReachedServer = mutation && startedSending.get())
        } catch (_: InterruptedIOException) {
            throw OpenVikingApiException(if (userCancelled.get() || Thread.currentThread().isInterrupted) OpenVikingErrorCode.CANCELLED else OpenVikingErrorCode.TIMEOUT,
                mayHaveReachedServer = mutation && startedSending.get())
        } catch (_: IOException) {
            throw OpenVikingApiException(if (userCancelled.get()) OpenVikingErrorCode.CANCELLED else OpenVikingErrorCode.NETWORK_ERROR,
                mayHaveReachedServer = mutation && startedSending.get())
        } finally { binding?.close() }
    }

    private fun healthObject(value: JsonElement): JsonObject {
        val obj = value as? JsonObject ?: throw OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR)
        val status = (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (status !in setOf("ok", "healthy")) throw OpenVikingApiException(OpenVikingErrorCode.PROTOCOL_ERROR)
        return obj
    }

    // Reuse TLS connections without retaining any credential or per-request cancellation hook.
    companion object { private val sharedTransport by lazy { OkHttpClient() } }
}
