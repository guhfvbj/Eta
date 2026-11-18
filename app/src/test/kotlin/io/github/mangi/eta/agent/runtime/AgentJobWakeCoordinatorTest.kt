package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.JobDelivery
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentJobWakeCoordinatorTest {
    private class Fixture {
        var exists = true
        var active = false
        var budgetAvailable = true
        var hasBase = true
        var wakeAccepted = true
        var throwOnExists = false
        var injections = 0
        var budgets = 0
        var loads = 0
        var wakes = 0
        var removals = 0
        val calls = mutableListOf<String>()
        val base = AgentContinuationBaseStore.Base("conversation", "run",
            AgentRuntimeWire.RunRequest("run", "原任务", AgentModelClient.ModelConfig(
                baseUrl = "", apiKey = "", model = "", systemPrompt = ""), emptyList()), null, emptyList(), 1)
        val coordinator = AgentJobWakeCoordinator(
            conversationExists = { calls += "exists"; if (throwOnExists) error("会话读取失败"); exists },
            injectIntoActiveRun = { _, _ -> calls += "inject"; injections++; active },
            wake = { _, _ -> calls += "wake"; wakes++; if (wakeAccepted) active = true; wakeAccepted },
            loadBase = { calls += "load"; loads++; base.takeIf { hasBase } },
            wakeBudget = object : AgentJobWakeCoordinator.WakeBudget {
                override fun tryConsume(conversationId: String): Boolean {
                    calls += "budget"; budgets++; return budgetAvailable
                }
            },
            notice = { "完成：${it.id}" }, removeBase = { removals++ },
        )
    }

    private fun task() = DetachedTask("job", 1, "随机标记", "printf done", "/tmp", "user",
        TerminalEnvironment.ANDROID, "/tmp/job.log", 1, commandTask = true,
        completedAt = 2, exitCode = 0, conversationId = "conversation", originRunId = "run")

    @Test fun missingOwnershipFallsBackWithoutSideEffects() {
        val f = Fixture()
        assertFalse(f.coordinator.onJobCompleted(task().copy(conversationId = null)))
        assertFalse(f.coordinator.onJobCompleted(task().copy(originRunId = null)))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun inlineCompletionIsSilent() {
        val f = Fixture()
        assertFalse(f.coordinator.onJobCompleted(task().copy(delivery = JobDelivery.INLINE)))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun consumedCompletionIsNotDelivered() {
        val f = Fixture()
        assertFalse(f.coordinator.onJobCompleted(task().copy(consumedAt = 3)))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun alreadyAttemptedCompletionIsNotDelivered() {
        val f = Fixture()
        assertFalse(f.coordinator.onJobCompleted(task().copy(deliveryAttemptedAt = 3)))
        assertTrue(f.calls.isEmpty())
    }

    @Test fun deletedConversationRemovesItsBaseAndFallsBack() {
        val f = Fixture().apply { exists = false }
        assertFalse(f.coordinator.onJobCompleted(task()))
        assertEquals(1, f.removals)
        assertEquals(listOf("exists"), f.calls)
    }

    @Test fun quietCompletionUsesNotificationWithoutBudget() {
        val f = Fixture()
        assertFalse(f.coordinator.onJobCompleted(task().copy(delivery = JobDelivery.QUIET)))
        assertEquals(listOf("exists"), f.calls)
    }

    @Test fun activeInjectionDoesNotConsumeWakeBudgetAndDuplicateDoesNotReinject() {
        val f = Fixture().apply { active = true }
        assertTrue(f.coordinator.onJobCompleted(task()))
        assertTrue(f.coordinator.onJobCompleted(task()))
        assertEquals(1, f.injections)
        assertEquals(0, f.budgets)
        assertEquals(0, f.loads)
        assertEquals(0, f.wakes)
    }

    @Test fun exhaustedBudgetStopsBeforeLoadingBase() {
        val f = Fixture().apply { budgetAvailable = false }
        assertFalse(f.coordinator.onJobCompleted(task()))
        assertEquals(listOf("exists", "inject", "budget"), f.calls)
    }

    @Test fun exhaustedBudgetReportsItsFallbackReasonOnce() {
        val f = Fixture().apply { budgetAvailable = false }
        val first = f.coordinator.onJobCompletedWithReason(task())
        assertTrue(first is io.github.mangi.eta.agent.terminal.JobCompletionOutcome.Fallback)
        assertEquals("已连续唤醒 3 次，本次仅通知",
            (first as io.github.mangi.eta.agent.terminal.JobCompletionOutcome.Fallback).reason)
        val second = f.coordinator.onJobCompletedWithReason(task())
        assertEquals(first, second)
        assertEquals(1, f.budgets)
        assertEquals(0, f.loads)
        assertEquals(0, f.wakes)
    }

    @Test fun wakeRejectionIsReportedAsFallbackReason() {
        val f = Fixture().apply { wakeAccepted = false }
        val outcome = f.coordinator.onJobCompletedWithReason(task())
        assertTrue(outcome is io.github.mangi.eta.agent.terminal.JobCompletionOutcome.Fallback)
        assertEquals("后台唤醒异常，本次仅通知",
            (outcome as io.github.mangi.eta.agent.terminal.JobCompletionOutcome.Fallback).reason)
    }

    @Test fun missingBaseConsumesBudgetOnlyOnce() {
        val f = Fixture().apply { hasBase = false }
        repeat(2) { assertFalse(f.coordinator.onJobCompleted(task())) }
        assertEquals(1, f.budgets)
        assertEquals(1, f.loads)
        assertEquals(0, f.wakes)
    }

    @Test fun wakeSucceedsAfterBudgetAndBaseAndIsNotRepeated() {
        val f = Fixture()
        assertTrue(f.coordinator.onJobCompleted(task()))
        assertTrue(f.coordinator.onJobCompleted(task()))
        assertEquals(listOf("exists", "inject", "budget", "load", "wake", "exists"), f.calls)
        assertEquals(1, f.wakes)
    }

    @Test fun wakeFailureFallsBackWithoutRetryingTheSameTask() {
        val f = Fixture().apply { wakeAccepted = false }
        repeat(2) { assertFalse(f.coordinator.onJobCompleted(task())) }
        assertEquals(1, f.wakes)
        assertEquals(1, f.budgets)
    }

    @Test fun subsequentCompletionJoinsTheNewActiveRunWithoutAnotherWake() {
        val f = Fixture()
        assertTrue(f.coordinator.onJobCompleted(task()))
        assertTrue(f.coordinator.onJobCompleted(task().copy(id = "second")))
        assertEquals(1, f.wakes)
        assertEquals(1, f.budgets)
        assertEquals(2, f.injections)
    }

    @Test fun externalFailuresFallBackToNotification() {
        val f = Fixture().apply { throwOnExists = true }
        assertFalse(f.coordinator.onJobCompleted(task()))
        assertEquals(0, f.wakes)
    }

    @Test fun noticeIsStableBoundedAndKeepsIncrementalReadGuidance() {
        val completedTask = task().copy(command = "  printf\n done  " + "x".repeat(300), cwd = "路".repeat(2000))
        val notice = AgentJobNotice.build(completedTask)
        assertEquals(notice, AgentJobNotice.build(completedTask))
        assertTrue(notice.startsWith("任务 job 的后台命令已完成\n退出码：0"))
        assertTrue(notice.length <= 1500)
        assertTrue(notice.endsWith("读取增量输出，不要轮询。"))
        assertTrue(notice.contains("`action=\"read\"`"))
        assertTrue(notice.contains("命令：printf done"))
        assertTrue(notice.contains("cwd："))
        assertTrue(notice.contains("delivery：wakeup"))
        assertTrue(notice.contains("…"))
        assertTrue(AgentJobNotice.build(task().copy(endReason = "stopped")).contains("被停止"))
        assertTrue(AgentJobNotice.build(task().copy(exitCode = 7)).contains("已终止"))
        assertTrue(AgentJobNotice.build(task().copy(endReason = "timed_out")).contains("结束原因：timed_out"))
    }
}
