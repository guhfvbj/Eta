package io.github.mangi.eta.agent.openviking

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenVikingHttpClientTest {
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val seen = CopyOnWriteArrayList<String>()
    private val handler = AtomicReference<(HttpExchange) -> Unit>({ reply(it, 200, """{"status":"ok","result":{}}""") })
    private lateinit var base: String

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            seen += exchange.requestURI.path
            try { handler.get().invoke(exchange) } finally { exchange.close() }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }
    @After fun stop() { server.stop(0); executor.shutdownNow() }

    private fun client(timeout: Long = 1000, cap: Int = 512 * 1024, cancellation: OpenVikingCancellation? = null) =
        OpenVikingHttpClient(base, "fixture-key", timeoutMs = timeout, cancellation = cancellation,
            maxResponseBytes = cap, mutationTimeoutMs = timeout, allowLoopbackHttpForTests = true)

    @Test fun contextModeHasNoListOnlyFieldsAndUsesAuthenticatedActor() {
        var body = ""
        handler.set { e ->
            assertEquals("Bearer fixture-key", e.requestHeaders.getFirst("Authorization"))
            assertEquals("eta", e.requestHeaders.getFirst("X-OpenViking-Actor-Peer"))
            assertNull(e.requestHeaders.getFirst("X-OpenViking-Account"))
            assertNull(e.requestHeaders.getFirst("X-OpenViking-User"))
            body = e.requestBody.bufferedReader().readText()
            reply(e, 200, """{"status":"ok","result":{"digest":"short","rendered":"long","stats":{"used_tokens":3}}}""")
        }
        assertEquals("short", client().contextSearch(ContextSearchRequest("remember" )).injectableText())
        val obj = Json.parseToJsonElement(body).jsonObject
        assertEquals("context", obj.getValue("mode").jsonPrimitive.content)
        assertFalse(obj.containsKey("target_uri"))
        assertFalse(obj.containsKey("read_content"))
        assertEquals("auto", obj.getValue("rewrite").jsonPrimitive.content)
        assertEquals(listOf("/api/v1/search/search"), seen)
    }

    @Test fun renderedFallbackAndNoRelevantAreRespected() {
        handler.set { reply(it, 200, """{"status":"ok","result":{"rendered":"fallback"}}""") }
        assertEquals("fallback", client().contextSearch(ContextSearchRequest("x")).injectableText())
        handler.set { reply(it, 200, """{"status":"ok","result":{"digest":"ignore","stats":{"rewrite":"no_relevant"}}}""") }
        assertEquals("", client().contextSearch(ContextSearchRequest("x")).injectableText())
    }

    @Test fun httpErrorsAreClassifiedAndMutation500IsAmbiguous() {
        for ((status, code) in listOf(401 to OpenVikingErrorCode.AUTH_FAILED, 429 to OpenVikingErrorCode.RATE_LIMITED,
            500 to OpenVikingErrorCode.PROTOCOL_ERROR)) {
            handler.set { reply(it, status, "private response must never appear in exception") }
            val failure = failure { client().createSession(CreateSessionRequest("eta-test")) }
            assertEquals(code, failure.code)
            assertEquals(status, failure.httpStatus)
            assertEquals(status == 500, failure.mayHaveReachedServer)
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }

    @Test fun malformedAndOversizedResponsesFailClosed() {
        handler.set { reply(it, 200, "not json") }
        assertEquals(OpenVikingErrorCode.PROTOCOL_ERROR, failure { client().status() }.code)
        handler.set { reply(it, 200, "x".repeat(1024)) }
        assertEquals(OpenVikingErrorCode.PROTOCOL_ERROR, failure { client(cap = 128).status() }.code)
    }

    @Test fun timeoutAndCooperativeCancellationAreDistinct() {
        handler.set { e -> Thread.sleep(250); reply(e, 200, """{"status":"ok","result":{}}""") }
        assertEquals(OpenVikingErrorCode.TIMEOUT, failure { client(timeout = 30).status() }.code)
        val cancelled = OpenVikingCancellationSource().apply { cancel() }
        assertEquals(OpenVikingErrorCode.CANCELLED, failure { client(cancellation = cancelled).status() }.code)
    }

    @Test fun connectionTestOnlyReadsHealthAndAuthenticatedStatus() {
        handler.set { e -> reply(e, 200, if (e.requestURI.path == "/health") """{"status":"ok"}""" else """{"status":"ok","result":{"version":"0.5.4"}}""") }
        client().testConnection()
        assertEquals(listOf("/health", "/api/v1/system/status"), seen)
    }

    @Test fun connectionTestRejectsAnUnrecognisedHealthDocument() {
        handler.set { reply(it, 200, "{}") }
        assertEquals(OpenVikingErrorCode.PROTOCOL_ERROR, failure { client().testConnection() }.code)
        assertEquals(listOf("/health"), seen)
    }

    @Test fun pathsAndUrisAreEncodedAndRedirectsNotFollowed() {
        handler.set { e ->
            if (e.requestURI.path == "/api/v1/fs/ls") assertTrue(e.requestURI.rawQuery.contains("%3A"))
            reply(e, 200, """{"status":"ok","result":[]}""")
        }
        client().list("viking://~/memories/a b")
        handler.set { e -> e.responseHeaders.add("Location", "$base/private"); reply(e, 302, "") }
        assertEquals(OpenVikingErrorCode.PROTOCOL_ERROR, failure { client().status() }.code)
        assertFalse(seen.contains("/private"))
    }

    @Test fun contentFindSessionAndTaskEndpointsUseTheVerifiedContract() {
        val methods = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        handler.set { e ->
            methods += e.requestMethod
            bodies += e.requestBody.bufferedReader().readText()
            val result = when {
                e.requestURI.path.endsWith("/commit") -> """{"task_id":"task-1"}"""
                e.requestURI.path.contains("/tasks/") -> """{"status":"completed","task_id":"task-1"}"""
                else -> "{}"
            }
            reply(e, 200, """{"status":"ok","result":$result}""")
        }
        val api = client()
        api.find(FindRequest("query", "viking://~/memories", readContent = true))
        listOf("abstract", "overview", "full").forEach { api.read("viking://~/memories/profile.md", it) }
        api.write(WriteContentRequest("viking://~/memories/fixture.md", "fixture text", mode = "overwrite"))
        api.delete("viking://~/memories/fixture.md")
        api.createSession(CreateSessionRequest("eta-fixture"))
        api.addMessages("eta-fixture", BatchAddMessageRequest(listOf(AddMessageRequest("user", "fixture", turnId = "turn-1"))))
        assertEquals("task-1", api.commit("eta-fixture").taskId)
        assertTrue(api.task("task-1").succeeded)
        assertEquals(listOf("POST", "GET", "GET", "GET", "POST", "DELETE", "POST", "POST", "POST", "GET"), methods)
        assertEquals(listOf("/api/v1/search/find", "/api/v1/content/abstract", "/api/v1/content/overview",
            "/api/v1/content/read", "/api/v1/content/write", "/api/v1/fs", "/api/v1/sessions",
            "/api/v1/sessions/eta-fixture/messages/batch", "/api/v1/sessions/eta-fixture/commit", "/api/v1/tasks/task-1"), seen)
        assertTrue(Json.parseToJsonElement(bodies[0]).jsonObject.getValue("read_content").jsonPrimitive.content.toBoolean())
        // Working-memory quality and resource policy remain the server's decision by default.
        assertFalse(Json.parseToJsonElement(bodies[8]).jsonObject.containsKey("enable_working_memory"))
    }

    @Test fun cancellationDuringMutationIsAmbiguousAndNeverSafeToBlindlyReplay() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        val cancelled = OpenVikingCancellationSource()
        handler.set { e ->
            entered.countDown()
            release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            reply(e, 200, """{"status":"ok","result":{}}""")
        }
        try {
            val result = worker.submit<OpenVikingApiException> {
                failure { client(timeout = 2000, cancellation = cancelled).createSession(CreateSessionRequest("eta-cancelled")) }
            }
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            cancelled.cancel()
            val error = result.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(OpenVikingErrorCode.CANCELLED, error.code)
            assertTrue(error.mayHaveReachedServer)
        } finally { release.countDown(); worker.shutdownNow() }
    }

    private fun failure(block: () -> Unit): OpenVikingApiException {
        try { block() } catch (failure: OpenVikingApiException) { return failure }
        throw AssertionError("Expected a classified API failure")
    }

    companion object {
        private fun reply(exchange: HttpExchange, status: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }
}
