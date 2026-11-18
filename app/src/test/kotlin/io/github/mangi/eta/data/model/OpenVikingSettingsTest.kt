package io.github.mangi.eta.data.model

import org.junit.Assert.*
import org.junit.Test

class OpenVikingSettingsTest {
    @Test fun defaultsMatchConfirmedProductDecisions() {
        val s = OpenVikingSettings()
        assertFalse(s.enabled)
        assertEquals("eta", s.actorPeer)
        assertEquals(OpenVikingPeerScope.ACTOR, s.peerScope)
        assertEquals(OpenVikingQueryExpansion.AUTO, s.queryExpansion)
        assertEquals(OpenVikingRewriteMode.AUTO, s.rewrite)
        assertTrue(s.captureAssistantReplies)
        assertEquals(3000, s.foregroundDeadlineMs)
        assertEquals(4000, s.commitTokenThreshold)
        assertEquals(8, s.commitTurnThreshold)
        assertEquals(60, s.minCommitIntervalSeconds)
        assertEquals(5, s.idleCommitMinutes)
        assertNull(s.connectionId())
        s.validate()
    }

    @Test fun urlNormalizationIsStableAndPreservesProxyPrefix() {
        assertEquals("https://example.com/proxy", OpenVikingSettings.normalizeBaseUrl(" HTTPS://Example.COM:443/proxy/// "))
        assertEquals("https://example.com:8443", OpenVikingSettings.normalizeBaseUrl("https://example.com:8443/"))
        for (url in listOf("http://example.com", "https://user:pass@example.com", "https://example.com/?key=x",
            "https://example.com/#key", "https://example.com/../api", "https://example.com/%2e%2e/api", "not a url")) {
            try { OpenVikingSettings.normalizeBaseUrl(url); fail("Should reject URL") }
            catch (_: OpenVikingValidationException) { }
        }
    }

    @Test fun connectionIdentityRotatesOnlyForServerPeerOrCredentialRevision() {
        val s = OpenVikingSettings(baseUrl = "https://EXAMPLE.com:443/", secretRevision = "revision-a")
        assertEquals(s.connectionId(), s.copy(baseUrl = "https://example.com").connectionId())
        assertEquals(s.connectionId(), s.copy(recallMaxTokens = 3000, enabled = true).connectionId())
        assertNotEquals(s.connectionId(), s.copy(actorPeer = "other").connectionId())
        assertNotEquals(s.connectionId(), s.copy(secretRevision = "revision-b").connectionId())
        assertNotEquals(s.connectionId(), s.copy(baseUrl = "https://elsewhere.example").connectionId())
        assertEquals(64, s.connectionId()!!.length)
    }

    @Test fun deadlinesAndFiniteScoresAreValidated() {
        val s = OpenVikingSettings()
        s.copy(foregroundDeadlineMs = 500).validate()
        s.copy(foregroundDeadlineMs = 15000).validate()
        for (bad in listOf(s.copy(foregroundDeadlineMs = 499), s.copy(foregroundDeadlineMs = 15001),
            s.copy(scoreThreshold = Double.NaN), s.copy(actorPeer = "eta\r\nInjected: header"))) {
            try { bad.validate(); fail("Should reject setting") } catch (_: OpenVikingValidationException) { }
        }
    }
}
