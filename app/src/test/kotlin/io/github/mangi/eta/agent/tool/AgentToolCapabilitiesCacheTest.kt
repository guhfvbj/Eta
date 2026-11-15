package io.github.mangi.eta.agent.tool

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AgentToolCapabilitiesCacheTest {
    @Test fun expiresAndInvalidatesAfterRootChecksOrGrantChanges() {
        var now = 0L
        var granted = false
        var captures = 0
        val cache = AgentToolCapabilitiesCache(
            capture = { captures++; AgentToolCapabilities(rootAvailable = granted) },
            rootGranted = { granted }, now = { now },
        )
        val first = cache()
        now = 1999
        assertSame(first, cache())
        assertEquals(1, captures)
        now = 2000
        assertNotSame(first, cache())
        assertEquals(2, captures)
        cache.invalidate() // check_root must refresh even when the grant did not change.
        cache()
        assertEquals(3, captures)
        granted = true
        assertTrue(cache().rootAvailable)
        assertEquals(4, captures)
        granted = false
        assertFalse(cache().rootAvailable)
        assertEquals(5, captures)
    }

    @Test fun concurrentReadersShareOneCaptureAndFailuresRemainRetryable() {
        val captures = AtomicInteger()
        val cache = AgentToolCapabilitiesCache(
            capture = {
                if (captures.incrementAndGet() == 1) error("temporary capture failure")
                AgentToolCapabilities(rootAvailable = false)
            },
            rootGranted = { false }, now = { 0L },
        )
        assertThrows(IllegalStateException::class.java) { cache() }
        val workers = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val results = List(16) { workers.submit<AgentToolCapabilities> {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                cache()
            } }
            start.countDown()
            val snapshots = results.map { it.get(5, TimeUnit.SECONDS) }
            assertTrue(snapshots.all { it === snapshots.first() })
            assertEquals(2, captures.get())
        } finally { workers.shutdownNow() }
    }
}
