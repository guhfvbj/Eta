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
        val first = CompletableFuture.supplyAsync { AgentToolAuthorization.confirm("first", "write_file", "{}", risk, firstController) }
        val second = CompletableFuture.supplyAsync { AgentToolAuthorization.confirm("second", "write_file", "{}", risk, secondController) }
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
}
