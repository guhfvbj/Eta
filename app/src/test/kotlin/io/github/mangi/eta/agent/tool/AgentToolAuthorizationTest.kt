package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class AgentToolAuthorizationTest {
    @Test fun confirmationOfOneConversationDoesNotAuthorizeAnother() {
        val firstController = AgentRunController()
        val secondController = AgentRunController()
        val risk = AgentRiskAssessment(AgentRiskLevel.HIGH, listOf("write"))
        val first = CompletableFuture.supplyAsync { AgentToolAuthorization.confirm("first", "write_file", "{}", risk, firstController, presentNotification = { true }) }
        val second = CompletableFuture.supplyAsync { AgentToolAuthorization.confirm("second", "write_file", "{}", risk, secondController, presentNotification = { true }) }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (AgentToolAuthorization.pending.value.size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
            val requests = AgentToolAuthorization.pending.value
            assertEquals(2, requests.size)
            AgentToolAuthorization.answer(requests.single { it.conversation == "first" }.id, true)
            assertTrue(first.get(3, TimeUnit.SECONDS))
            assertFalse(second.isDone)
            AgentToolAuthorization.answer(requests.single { it.conversation == "second" }.id, false)
            assertFalse(second.get(3, TimeUnit.SECONDS))
        } finally { firstController.cancel(); secondController.cancel() }
    }
    @Test fun noForegroundHostAndNoNotificationFailsImmediately() {
        val started = System.nanoTime()
        val result = AgentToolAuthorization.confirm("background", "write_file", "{}", AgentRiskAssessment(AgentRiskLevel.HIGH), AgentRunController(), presentNotification = { false })
        assertFalse(result)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<1000)
        assertTrue(AgentToolAuthorization.pending.value.isEmpty())
    }
    @Test fun sessionGrantSurvivesNewRunButNotDifferentConversationArgumentsOrPolicy() {
        AgentToolAuthorization.clearGrants()
        AgentToolAuthorization.grant("one","set_volume","{level:2}","standard")
        assertTrue(AgentToolAuthorization.hasGrant("one","set_volume","{level:2}","standard"))
        assertFalse(AgentToolAuthorization.hasGrant("two","set_volume","{level:2}","standard"))
        assertFalse(AgentToolAuthorization.hasGrant("one","set_volume","{level:3}","standard"))
        assertFalse(AgentToolAuthorization.hasGrant("one","set_volume","{level:2}","strict"))
        AgentToolAuthorization.clearConversation("one")
        assertFalse(AgentToolAuthorization.hasGrant("one","set_volume","{level:2}","standard"))
    }
}
