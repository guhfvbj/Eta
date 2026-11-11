package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class AgentModelRetryTest {
    @Test
    fun retriesAreBoundedAndBackoffIsPerModelRound() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ -> calls++; throw SocketTimeoutException("timeout") })
        }
        assertEquals(4, calls)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertTrue(failure.message.orEmpty().contains("已重试 3 次"))
        delays.clear()
        calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) throw IOException("connection reset")
            response()
        })
        assertEquals(2, result.round)
        assertEquals(listOf(2_000L), delays)
    }

    @Test
    fun cancellationDuringBackoffStopsBeforeAnotherRequest() {
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            complete(
                AgentModelRetry { control, _ -> control.cancel() },
                provider { _, _ -> calls++; throw SocketTimeoutException() },
                controller,
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun callbackFailuresAndHostedToolFailuresDoNotReplayProvider() {
        val noRetry = AgentModelRetry { _, _ -> fail("不应重试") }
        val callbackFailure = IOException("checkpoint write failed")
        val thrown = assertThrows(IOException::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.RequestStarted)
                response()
            }, onProviderEvent = { _, _ -> throw callbackFailure })
        }
        assertSame(callbackFailure, thrown)
        assertThrows(AgentModelFailure::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.HostedToolStarted("search-1", "web_search"))
                throw SocketTimeoutException()
            })
        }
    }

    @Test
    fun classifiesTransientFailuresWithoutRetryingPermanentFailures() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 529)) {
            assertTrue(AgentModelFailure.http(status, "").retryable)
        }
        for (status in listOf(400, 401, 403, 404)) {
            assertFalse(AgentModelFailure.http(status, "").retryable)
        }
        assertFalse(AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""").retryable)
        assertNull(AgentModelFailure.transport(SSLHandshakeException("certificate")))
        assertNull(AgentModelFailure.transport(org.json.JSONException("invalid JSON")))
        assertTrue(AgentModelFailure.stream(JSONObject().put("type", "overloaded_error"), "过载").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("type", "authentication_error"), "认证失败").retryable)
        assertFalse(AgentModelFailure.http(503, "secret request text").message.orEmpty().contains("secret"))
    }

    @Test
    fun averageDurationAndUsageBelongOnlyToTheLatestSuccessfulAttempt() {
        var now = 0L
        var attempts = 0
        val events = mutableListOf<AgentEvent>()
        val retry = AgentModelRetry(nanoTime = { now }, waitBeforeRetry = { _, _ -> now += 8_000_000_000L })
        complete(retry, provider { _, emit ->
            emit(ProviderEvent.RequestStarted)
            emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 120, contextTokens = 120)))
            if (attempts++ == 0) {
                now += 5_000_000_000L
                throw SocketTimeoutException()
            }
            now += 2_000_000_000L
            emit(ProviderEvent.Usage(AgentTokenUsage(outputTokens = 80)))
            response()
        }, onEvent = { events += it })
        val usage = events.filterIsInstance<AgentEvent.UsageReceived>().single()
        assertEquals(2, usage.round)
        assertEquals(2000L, usage.usage.requestDurationMs)
        assertEquals(120, usage.usage.inputTokens)
        assertEquals(80, usage.usage.outputTokens)
    }

    @Test
    fun messageOnlyRateLimitIsRetriedWithinBudgetAndRecovers() {
        // Responses response.failed 只给 error.message，没有结构错误码。
        val rateLimit = AgentModelFailure.stream(
            JSONObject().put("message", "Upstream rate limit exceeded, please retry later"),
            "模型接口 Responses 请求失败：Upstream rate limit exceeded, please retry later",
        )
        assertTrue(rateLimit.retryable)

        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        val recovered = complete(retry, provider { _, _ ->
            if (calls++ == 0) throw rateLimit
            response()
        })
        assertEquals(2, recovered.round)
        assertEquals(listOf(2_000L), delays)

        delays.clear()
        calls = 0
        val exhausted = assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ ->
                calls++
                throw rateLimit
            })
        }
        assertEquals(4, calls)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertFalse(exhausted.retryable)
    }

    @Test
    fun structuredSignalsOutrankRateLimitText() {
        assertTrue(AgentModelFailure.stream(
            JSONObject().put("type", "rate_limit_exceeded").put("message", "slow down"),
            "限流",
        ).retryable)
        assertTrue(AgentModelFailure.stream(
            JSONObject().put("status", "429").put("message", "slow down"),
            "限流",
        ).retryable)
        // 只有泛化信号时才允许按文案兜底。
        assertTrue(AgentModelFailure.stream(
            JSONObject().put("type", "error")
                .put("message", "Upstream rate limit exceeded, please retry later"),
            "限流",
        ).retryable)
        assertTrue(AgentModelFailure.stream(
            JSONObject().put("code", JSONObject.NULL).put("type", JSONObject.NULL)
                .put("message", "Upstream rate limit exceeded, please retry later"),
            "限流",
        ).retryable)
        // 未知错误不能因为文案里出现 retry later 就重试。
        assertFalse(AgentModelFailure.stream(
            JSONObject().put("message", "Internal error, please retry later"),
            "未知错误",
        ).retryable)
        assertFalse(AgentModelFailure.stream(
            JSONObject().put("code", "some_new_error")
                .put("message", "Upstream rate limit exceeded, please retry later"),
            "未知错误",
        ).retryable)
    }

    @Test
    fun authenticationBillingQuotaAndContextFailuresStayUnretryable() {
        val rateLimitText = "Upstream rate limit exceeded, please retry later"
        assertFalse(AgentModelFailure.stream(
            JSONObject().put("type", "authentication_error").put("message", rateLimitText),
            "认证失败",
        ).retryable)
        assertFalse(AgentModelFailure.stream(
            JSONObject().put("status", "UNAUTHENTICATED").put("message", rateLimitText),
            "认证失败",
        ).retryable)
        for (code in listOf("insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached")) {
            assertFalse(AgentModelFailure.stream(
                JSONObject().put("code", code).put("message", rateLimitText),
                "额度受限",
            ).retryable)
        }
        assertFalse(AgentModelFailure.stream(
            JSONObject().put("message", "Billing issue: rate limit exceeded"),
            "计费失败",
        ).retryable)
        val overflow = AgentModelFailure.stream(
            JSONObject().put("code", "context_length_exceeded").put("message", rateLimitText),
            "上下文超限",
        )
        assertEquals(AgentModelFailure.CONTEXT_OVERFLOW, overflow.code)
        assertFalse(overflow.retryable)
    }

    @Test
    fun hostedToolStartBlocksMessageOnlyRateLimitRetry() {
        val rateLimit = AgentModelFailure.stream(
            JSONObject().put("message", "Upstream rate limit exceeded, please retry later"),
            "模型接口 Responses 请求失败：Upstream rate limit exceeded, please retry later",
        )
        var calls = 0
        val noRetry = AgentModelRetry { _, _ -> fail("不应重试") }
        val thrown = assertThrows(AgentModelFailure::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.HostedToolStarted("call-1", "web_search"))
                calls++
                throw rateLimit
            })
        }
        assertEquals(1, calls)
        assertFalse(thrown.retryable)
    }

    private fun complete(
        retry: AgentModelRetry,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
        onProviderEvent: (Int, ProviderEvent) -> Unit = { _, _ -> },
        onEvent: (AgentEvent) -> Unit = {},
    ) = retry.complete(
        initialRound = 1,
        initialRequest = ProviderRequest(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
            JSONArray(), JSONArray(),
        ),
        provider = provider,
        controller = controller,
        onEvent = onEvent,
        onProviderEvent = onProviderEvent,
        discardAttemptReasoning = {},
    )

    private fun response() = ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))

    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ) = action(request, onEvent)
        }
}
