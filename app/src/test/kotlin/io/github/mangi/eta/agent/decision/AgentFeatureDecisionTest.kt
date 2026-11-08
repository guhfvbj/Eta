package io.github.mangi.eta.agent.decision

import org.junit.Assert.*
import org.junit.Test

class AgentFeatureDecisionTest {
    @Test fun ordinaryJsonDecisionsValidateLabelsAndConfidence() {
        val result = AgentDecisionJson.parse("{\"groups\":[\"web\"],\"confidence\":0.8,\"needs_tools\":true}", setOf("web"))
        assertEquals(setOf("web"), result.selected)
        assertTrue(AgentDecisionJson.parse("{\"groups\":[\"root\"],\"confidence\":0.8}", setOf("web")).selected.isEmpty())
        assertThrows(Exception::class.java) { AgentDecisionJson.parse("{\"groups\":[],\"confidence\":8}", setOf("web")) }
    }
    @Test fun contradictoryContinuationKeepsValidGroupsWithoutFallback() {
        val result=AgentDecisionJson.parse("""{"needs_tools":false,"groups":["screen","device","calendar","personal","device_state","extensions"],"confidence":0.9}""",
            setOf("screen","device","calendar","personal","device_state","extensions"))
        assertTrue(result.needsTools)
        assertEquals(6,result.selected.size)
    }
    @Test fun basicUnknownAndNonStringLabelsDoNotPoisonValidSelection() {
        val result=AgentDecisionJson.parse("""{"needs_tools":false,"groups":["basic"," terminal ","typo",7,null,""],"confidence":0.9}""",setOf("terminal"))
        assertEquals(setOf("terminal"),result.selected)
        assertTrue(result.needsTools)
        val chat=AgentDecisionJson.parse("""{"needs_tools":false,"groups":["basic"],"confidence":0.9}""",setOf("terminal"))
        assertFalse(chat.needsTools);assertTrue(chat.selected.isEmpty())
    }
    @Test fun systemOneEndpointsAreNormalizedWithoutAcceptingQueryCredentials() {
        assertEquals("https://example.test/v1/systemone", (AgentDecisionEndpoint.normalize("https://example.test/v1/chat/completions") as AgentDecisionEndpoint.Resolution.Success).url)
        assertTrue(AgentDecisionEndpoint.normalize("https://example.test/v1?key=secret") is AgentDecisionEndpoint.Resolution.Invalid)
    }
}
