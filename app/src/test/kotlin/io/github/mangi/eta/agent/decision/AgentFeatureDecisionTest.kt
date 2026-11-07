package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.AgentFeatureReview
import org.junit.Assert.*
import org.junit.Test

class AgentFeatureDecisionTest {
    @Test fun ordinaryJsonDecisionsValidateLabelsAndConfidence() {
        val result = AgentFeatureDecision.parse("{\"groups\":[\"web\"],\"confidence\":0.8,\"needs_tools\":true}", setOf("web"))
        assertEquals(setOf("web"), result.selected)
        assertThrows(Exception::class.java) { AgentFeatureDecision.parse("{\"groups\":[\"root\"],\"confidence\":0.8}", setOf("web")) }
        assertThrows(Exception::class.java) { AgentFeatureDecision.parse("{\"groups\":[],\"confidence\":8}", setOf("web")) }
    }
    @Test fun missingReviewDecisionCannotBecomeAnApproval() {
        assertTrue(AgentFeatureReview.parse("{\"allow\":true}"))
        assertFalse(AgentFeatureReview.parse("{\"allow\":false}"))
        assertThrows(Exception::class.java) { AgentFeatureReview.parse("{}") }
        assertThrows(Exception::class.java) { AgentFeatureReview.parse("{\"allow\":\"true\"}") }
    }
    @Test fun systemOneEndpointsAreNormalizedWithoutAcceptingQueryCredentials() {
        assertEquals("https://example.test/v1/systemone", (AgentDecisionEndpoint.normalize("https://example.test/v1/chat/completions") as AgentDecisionEndpoint.Resolution.Success).url)
        assertTrue(AgentDecisionEndpoint.normalize("https://example.test/v1?key=secret") is AgentDecisionEndpoint.Resolution.Invalid)
    }
}
