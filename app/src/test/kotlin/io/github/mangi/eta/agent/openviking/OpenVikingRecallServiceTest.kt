package io.github.mangi.eta.agent.openviking

import io.github.mangi.eta.data.model.OpenVikingSettings
import io.github.mangi.eta.data.model.OpenVikingPeerScope
import io.github.mangi.eta.data.model.OpenVikingQueryExpansion
import io.github.mangi.eta.data.model.OpenVikingRewriteMode
import io.github.mangi.eta.data.model.OpenVikingPurpose
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class OpenVikingRecallServiceTest {
    private val settings = OpenVikingSettings(enabled = true, foregroundDeadlineMs = 500)
    private val service = OpenVikingRecallService(onFailure = {})
    @Test fun digestRenderedAndNoRelevantFollowTheContextContract() {
        for ((response, expected) in listOf(ContextSearchResponse(digest = "digest", rendered = "rendered") to "digest",
            ContextSearchResponse(rendered = "rendered") to "rendered",
            ContextSearchResponse(digest = "ignore", stats = ContextSearchStats(rewrite = "no_relevant")) to "")) {
            assertEquals(expected, service.recall(settings, "query", "session", OpenVikingCancellationSource(), OpenVikingContextSearch { response }))
        }
    }
    @Test fun hardDeadlineCancelsWithoutRetryAndFailureOpens() {
        val calls = AtomicInteger()
        val cancelled = CountDownLatch(1)
        val source = OpenVikingCancellationSource()
        source.register { cancelled.countDown() }
        val start = System.nanoTime()
        val result = service.recall(settings, "query", null, source, OpenVikingContextSearch {
            calls.incrementAndGet()
            Thread.sleep(5000)
            ContextSearchResponse(digest = "too late")
        })
        assertEquals("", result)
        assertEquals(1, calls.get())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
        assertEquals(0L, cancelled.count)
        assertEquals("", service.recall(settings, "query", null, OpenVikingCancellationSource(), OpenVikingContextSearch { throw IllegalStateException() }))
    }
    @Test fun cooperativeCancellationEndsAnInFlightRecall() {
        val entered = CountDownLatch(1)
        val source = OpenVikingCancellationSource()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<String> { service.recall(settings.copy(foregroundDeadlineMs = 15000), "query", null, source,
                OpenVikingContextSearch { entered.countDown(); Thread.sleep(10000); ContextSearchResponse() }) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            source.cancel()
            assertEquals("", result.get(2, TimeUnit.SECONDS))
        } finally { worker.shutdownNow() }
    }
    @Test fun flagsAndEveryRecallTuningFieldAreApplied() {
        val tuned = settings.copy(queryExpansion = OpenVikingQueryExpansion.OFF, rewrite = OpenVikingRewriteMode.ON,
            peerScope = OpenVikingPeerScope.ALL, purpose = OpenVikingPurpose.CODING, recallMaxTokens = 1234, dedupTurns = 9, scoreThreshold = 0.7)
        service.recall(tuned, "query", "session", OpenVikingCancellationSource(), OpenVikingContextSearch {
            assertEquals("context", it.mode); assertEquals("session", it.sessionId)
            assertEquals("off", it.queryExpansion); assertEquals("true", it.rewrite.toString())
            assertEquals("all", it.peerScope); assertEquals("coding", it.purpose)
            assertEquals(1234, it.maxTokens); assertEquals(9, it.dedupTurns); assertEquals(0.7, it.scoreThreshold, 0.0)
            ContextSearchResponse()
        })
        val never = OpenVikingContextSearch { throw AssertionError("Must not call") }
        assertEquals("", service.recall(settings.copy(enabled = false), "query", null, OpenVikingCancellationSource(), never))
        assertEquals("", service.recall(settings.copy(autoRecall = false), "query", null, OpenVikingCancellationSource(), never))
    }
}
