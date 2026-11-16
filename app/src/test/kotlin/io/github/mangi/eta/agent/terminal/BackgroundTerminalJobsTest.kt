package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackgroundTerminalJobsTest {
    @Test fun cancellingPromotableForegroundCommandKeepsInlineDelivery() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val controller = controller(supervisor, jobs)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val running = worker.submit<String> { controller.executeTool("terminal", JSONObject()
                .put("command", "sleep 30").put("cwd", temporary.root.path)
                .put("promote_on_timeout", true).put("timeout_ms", 30_000)) }
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (supervisor.list().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(supervisor.list().isNotEmpty())
            controller.interruptAll()
            running.get(5, java.util.concurrent.TimeUnit.SECONDS)
            jobs.list(false)
            assertEquals(JobDelivery.INLINE, supervisor.list().single().task.delivery)
            assertEquals(0, notifications.get())
        } finally { controller.closeAll(); worker.shutdownNow() }
    }

    @Test fun stoppingRunningCommandConsumesTheResultBeforeAutomaticDelivery() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val id = JSONObject(jobs.start(supervisor, "sleep 30", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "模型主动停止", 0)).getString("job_id")
        assertEquals("stopped", JSONObject(jobs.stop(id)).getString("status"))
        assertTrue(supervisor.findTask(id)!!.consumedAt != null)
        assertEquals(0, notifications.get())
    }

    /** 通知合同先观察未消费任务；read/wait/stop 的主动消费另由新增用例验证。 */
    private fun awaitCompletionNotification(supervisor: DetachedTaskSupervisor, id: String) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (supervisor.findTask(id)?.completionNotified != true && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("无归属且未消费的任务完成后仍须通知", supervisor.findTask(id)?.completionNotified == true)
    }

    private fun completedFixture(delivery: JobDelivery = JobDelivery.WAKEUP): Pair<DetachedTaskSupervisor, String> {
        val log = File(temporary.root, "finished.log").apply { writeText("完成输出") }
        val task = DetachedTask("finished", Long.MAX_VALUE, "标记", "true", temporary.root.path, "user",
            TerminalEnvironment.ANDROID, log.path, 1, commandTask = true, completedAt = 2, exitCode = 0,
            conversationId = "conversation", originRunId = "run", delivery = delivery)
        File(temporary.root, "tasks.json").writeText(org.json.JSONArray().put(task.toRecordJson()).toString())
        return supervisor() to task.id
    }

    @Test fun successfulTerminalReadsConsumeAndSuppressPendingDelivery() {
        val (supervisor, id) = completedFixture()
        val jobs = host(supervisor)
        val read = JSONObject(jobs.read(id))
        assertEquals("完成输出", read.getString("output"))
        assertTrue(read.getBoolean("consumed"))
        val firstConsumedAt = supervisor.findTask(id)!!.consumedAt
        assertTrue(firstConsumedAt != null)
        jobs.read(id, fromOffset = 0)
        jobs.list(false)
        assertEquals(firstConsumedAt, supervisor.findTask(id)!!.consumedAt)
        assertEquals(0, notifications.get())
    }

    @Test fun terminalIncrementalReadConsumesWithoutChangingDelivery() {
        val (supervisor, id) = completedFixture()
        val jobs = host(supervisor)
        assertTrue(JSONObject(jobs.read(id, fromOffset = 0)).getBoolean("ok"))
        assertTrue(supervisor.findTask(id)!!.consumedAt != null)
        assertEquals(JobDelivery.WAKEUP, supervisor.findTask(id)!!.delivery)
        jobs.list(false)
        assertEquals(0, notifications.get())
    }

    @Test fun failedTerminalReadDoesNotConsume() {
        val (supervisor, id) = completedFixture()
        File(temporary.root, "finished.log").delete()
        val jobs = host(supervisor)
        assertFalse(JSONObject(jobs.read(id)).getBoolean("ok"))
        assertEquals(null, supervisor.findTask(id)!!.consumedAt)
    }

    @Test fun terminalWaitConsumesBeforeRefreshingHost() {
        val (supervisor, id) = completedFixture()
        val jobs = host(supervisor)
        assertFalse(JSONObject(jobs.waitFor(id, 0) { false }).getBoolean("running"))
        assertTrue(supervisor.findTask(id)!!.consumedAt != null)
        assertEquals(0, notifications.get())
    }

    @Test fun terminalStopConsumesBeforeRefreshingHost() {
        val (supervisor, id) = completedFixture()
        val jobs = host(supervisor)
        assertTrue(JSONObject(jobs.stop(id)).getBoolean("ok"))
        assertTrue(supervisor.findTask(id)!!.consumedAt != null)
        assertEquals(0, notifications.get())
    }

    @Test fun inlineCompletionNeverCallsSinkOrNotification() {
        val (supervisor, _) = completedFixture(JobDelivery.INLINE)
        val jobs = host(supervisor)
        var sinkCalls = 0
        jobs.completionSink = object : JobCompletionSink {
            override fun onJobCompleted(task: DetachedTask): Boolean { sinkCalls++; return true }
        }
        jobs.resume()
        jobs.list(false)
        assertEquals(0, sinkCalls)
        assertEquals(0, notifications.get())
    }

    @Test fun handledSinkMarksAttemptAndSkipsNotification() {
        val (supervisor, id) = completedFixture()
        val jobs = host(supervisor)
        var calls = 0
        jobs.completionSink = object : JobCompletionSink {
            override fun onJobCompleted(task: DetachedTask): Boolean { calls++; return true }
        }
        jobs.resume()
        jobs.list(false)
        assertEquals(1, calls)
        assertEquals(0, notifications.get())
        assertTrue(supervisor.findTask(id)!!.deliveryAttemptedAt != null)
        assertTrue(supervisor.findTask(id)!!.completionNotified)
        assertTrue(JSONObject(jobs.list(false)).getJSONArray("tasks").getJSONObject(0).getBoolean("wake_attempted"))
    }

    @Test fun declinedSinkFallsBackToNotificationOnlyOnce() {
        val (supervisor, id) = completedFixture()
        val jobs = host(supervisor)
        jobs.completionSink = object : JobCompletionSink { override fun onJobCompleted(task: DetachedTask) = false }
        jobs.resume()
        jobs.list(false)
        assertEquals(1, notifications.get())
        assertEquals(null, supervisor.findTask(id)!!.deliveryAttemptedAt)
        assertTrue(supervisor.findTask(id)!!.completionNotified)
    }

    @Test fun originlessCompletionStillUsesFallbackNotification() {
        val (supervisor, id) = completedFixture()
        val file = File(temporary.root, "tasks.json")
        file.writeText(org.json.JSONArray().put(supervisor.findTask(id)!!.copy(conversationId = null, originRunId = null).toRecordJson()).toString())
        val jobs = host(supervisor)
        jobs.completionSink = io.github.mangi.eta.agent.runtime.AgentJobWakeCoordinator(
            conversationExists = { error("无归属不能查询会话") },
            injectIntoActiveRun = { _, _ -> error("无归属不能注入") },
            wake = { _, _ -> error("无归属不能唤醒") }, loadBase = { null },
            wakeBudget = object : io.github.mangi.eta.agent.runtime.AgentJobWakeCoordinator.WakeBudget {
                override fun tryConsume(conversationId: String): Boolean = error("无归属不能扣预算")
            }, removeBase = {})
        jobs.resume()
        assertEquals(1, notifications.get())
    }

    @Test fun completionIsDispatchedBeforeForegroundLeaseRelease() {
        val supervisor = supervisor()
        val leased = java.util.concurrent.atomic.AtomicBoolean(false)
        val dispatched = java.util.concurrent.CountDownLatch(1)
        val released = java.util.concurrent.CountDownLatch(1)
        val leaseWasHeld = java.util.concurrent.atomic.AtomicBoolean(false)
        val jobs = BackgroundTerminalJobs(supervisor, NoopLogger,
            acquireLease = { leased.set(true); true },
            releaseLease = { leased.set(false); released.countDown() },
            onCompleted = { notifications.incrementAndGet() }).also { hosts += it }
        jobs.completionSink = object : JobCompletionSink {
            override fun onJobCompleted(task: DetachedTask): Boolean {
                leaseWasHeld.set(leased.get()); dispatched.countDown(); return true
            }
        }
        jobs.start(supervisor, "printf done", temporary.root.path, "user", TerminalEnvironment.ANDROID,
            "租约接力", 0, origin = JobOrigin("conversation", "run"))
        assertTrue(dispatched.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(leaseWasHeld.get())
        assertEquals(0, notifications.get())
    }

    @Test fun promotableForegroundCompletionRemainsInlineAndSilent() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val controller = controller(supervisor, jobs)
        try {
            val result = JSONObject(controller.executeTool("terminal", JSONObject().put("command", "printf done")
                .put("cwd", temporary.root.path).put("promote_on_timeout", true).put("timeout_ms", 5000)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val task = supervisor.list().single().task
            assertEquals(JobDelivery.INLINE, task.delivery)
            jobs.list(false)
            assertEquals(0, notifications.get())
        } finally { controller.closeAll() }
    }

    @Test fun promotionPreservesOwnershipAndChoosesWakeupOrQuiet() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val controller = RootShellTerminalController(NoopLogger, detachedSupervisor = supervisor,
            backgroundJobs = jobs, rootAvailable = { false }, origin = JobOrigin("conversation", "run"))
        try {
            for (delivery in listOf(JobDelivery.WAKEUP, JobDelivery.QUIET)) {
                val args = JSONObject().put("command", "sleep 30").put("cwd", temporary.root.path)
                    .put("promote_on_timeout", true).put("timeout_ms", 10)
                if (delivery == JobDelivery.QUIET) args.put("background_delivery", "quiet")
                val result = JSONObject(controller.executeTool("terminal", args))
                assertEquals("promoted", result.getString("status"))
                val task = supervisor.findTask(result.getString("job_id"))!!
                assertEquals(delivery, task.delivery)
                assertEquals("conversation", task.conversationId)
                assertEquals("run", task.originRunId)
                jobs.stop(task.id)
            }
        } finally { controller.closeAll() }
    }


    @get:Rule val temporary = TemporaryFolder()
    private val hosts = mutableListOf<BackgroundTerminalJobs>()
    private val notifications = AtomicInteger()

    private fun supervisor() = DetachedTaskSupervisor(NoopLogger, File(temporary.root, "tasks.json"),
        daemonDir = File(temporary.root, "logs").path, rootAvailable = { false })

    private fun host(supervisor: DetachedTaskSupervisor) = BackgroundTerminalJobs(supervisor, NoopLogger,
        onCompleted = { notifications.incrementAndGet() }).also { hosts += it }

    private fun controller(supervisor: DetachedTaskSupervisor, jobs: BackgroundTerminalJobs) =
        RootShellTerminalController(NoopLogger, detachedSupervisor = supervisor, backgroundJobs = jobs, rootAvailable = { false })

    @After fun closeHosts() { hosts.forEach { it.close() } }

    @Test fun statusViewsIncludeBoundedTailAndElapsedTimeWithoutChangingReadOutput() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val started = JSONObject(jobs.start(supervisor,
            "head -c 4000 /dev/zero | tr '\\000' x; printf THE_END", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "尾部预览", 5000))
        assertTrue(started.getLong("elapsed_ms") >= 0L)
        val id = started.getString("job_id")
        val waited = JSONObject(jobs.waitFor(id, 5000) { false })
        assertFalse(waited.getBoolean("running"))
        assertEquals(waited.getLong("completed_at") - waited.getLong("started_at"), waited.getLong("elapsed_ms"))
        assertTrue(waited.getString("output_tail").endsWith("THE_END"))
        assertTrue(waited.getString("output_tail").length <= 1024)
        val listed = JSONObject(jobs.list(false)).getJSONArray("tasks").getJSONObject(0)
        assertEquals(waited.getLong("elapsed_ms"), listed.getLong("elapsed_ms"))
        assertEquals(waited.getString("output_tail"), listed.getString("output_tail"))
        val read = JSONObject(jobs.read(id, 2048))
        assertEquals(2048, read.getString("output").length)
        assertTrue(read.getBoolean("output_truncated"))
        assertFalse(read.has("output_tail"))
        val stopped = JSONObject(jobs.stop(id))
        assertEquals(waited.getString("output_tail"), stopped.getString("output_tail"))
    }

    @Test fun commandContinuesAfterReplyAndItsResultIsVisibleToTheNextRun() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val first = controller(supervisor, jobs)
        val started = JSONObject(first.executeTool("terminal_job", JSONObject().put("action", "start")
            .put("command", "sleep 0.3; printf '完成\\n'").put("description", "生成报告").put("cwd", temporary.root.path)))
        assertTrue(started.toString(), started.getBoolean("ok"))
        first.closeAll()
        val second = controller(supervisor, jobs)
        try {
            val jobId = started.getString("job_id")
            awaitCompletionNotification(supervisor, jobId)
            val waited = JSONObject(second.executeTool("terminal_job", JSONObject().put("action", "wait")
                .put("job_id", jobId).put("wait_ms", 5000)))
            assertFalse(waited.toString(), waited.getBoolean("running"))
            val result = JSONObject(second.executeTool("terminal_job", JSONObject().put("action", "read").put("job_id", jobId)))
            assertEquals("completed", result.getString("status"))
            assertEquals(0, result.getInt("exit_code"))
            assertEquals("完成\n", result.getString("output"))
            assertEquals("完成\n", File(result.getString("output_path")).readText())
            assertEquals(1, notifications.get())
            assertEquals(1, JSONObject(jobs.list(false)).getJSONArray("tasks").length())
            assertEquals(0, JSONObject(jobs.list(true)).getJSONArray("tasks").length())
        } finally { second.closeAll() }
    }

    @Test fun fullOutputSurvivesPreviewTruncationAndHostRecreation() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val started = JSONObject(jobs.start(supervisor, "head -c 70000 /dev/zero | tr '\\000' x", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "长日志", 5000))
        val id = started.getString("job_id")
        awaitCompletionNotification(supervisor, id)
        jobs.waitFor(id, 5000) { false }
        val result = JSONObject(jobs.read(id, 1024))
        assertEquals("completed", result.getString("status"))
        assertEquals(1024, result.getString("output").length)
        assertTrue(result.getBoolean("output_truncated"))
        assertEquals(70000L, File(result.getString("output_path")).length())
        assertEquals(1, notifications.get())
        jobs.close()
        val restored = host(supervisor())
        restored.resume()
        val reread = JSONObject(restored.read(id))
        assertEquals("completed", reread.getString("status"))
        assertEquals(result.getString("output_path"), reread.getString("output_path"))
        assertEquals(1, notifications.get())
    }

    @Test fun failureTimeoutAndStopRemainDistinguishable() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val failed = JSONObject(jobs.start(supervisor, "printf problem; exit 7", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "失败任务", 5000)).getString("job_id")
        awaitCompletionNotification(supervisor, failed)
        jobs.waitFor(failed, 5000) { false }
        val failure = JSONObject(jobs.read(failed))
        assertEquals("failed", failure.getString("status"))
        assertEquals(7, failure.getInt("exit_code"))
        val expired = JSONObject(jobs.start(supervisor, "sleep 30", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "超时任务", 100)).getString("job_id")
        awaitCompletionNotification(supervisor, expired)
        jobs.waitFor(expired, 5000) { false }
        assertEquals("timed_out", JSONObject(jobs.read(expired)).getString("status"))
        val stopped = JSONObject(jobs.start(supervisor, "sleep 30", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "停止任务", 0)).getString("job_id")
        assertTrue(supervisor.stopCommand(stopped))
        awaitCompletionNotification(supervisor, stopped)
        assertEquals("stopped", JSONObject(jobs.stop(stopped)).getString("status"))
        assertEquals("stopped", JSONObject(jobs.read(stopped)).getString("status"))
        assertEquals(3, notifications.get())
    }

    @Test fun workingDirectoryFailureIsPreservedInTheLog() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val started = JSONObject(jobs.start(supervisor, "printf must-not-run", File(temporary.root, "missing").path,
            "user", TerminalEnvironment.ANDROID, "错误目录", 5000))
        val id = started.getString("job_id")
        jobs.waitFor(id, 5000) { false }
        val result = JSONObject(jobs.read(id))
        assertEquals("failed", result.getString("status"))
        assertTrue(result.getInt("exit_code") != 0)
        assertTrue(result.getString("output").contains("missing"))
        assertFalse(result.getString("output").contains("must-not-run"))
    }

    @Test fun fastCommandsKeepTheirResultsAndNotifyOnce() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        repeat(12) { index ->
            val started = JSONObject(jobs.start(supervisor, "printf '$index'", temporary.root.path,
                "user", TerminalEnvironment.ANDROID, "快速命令", 5000))
            assertTrue(started.toString(), started.getBoolean("ok"))
            val id = started.getString("job_id")
            awaitCompletionNotification(supervisor, id)
            jobs.waitFor(id, 5000) { false }
            assertEquals(index.toString(), JSONObject(jobs.read(id)).getString("output"))
        }
        assertEquals(12, notifications.get())
    }

    @Test fun cancellingOrTimingOutTheWaitDoesNotStopTheCommand() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val id = JSONObject(jobs.start(supervisor, "sleep 30", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "等待测试", 0)).getString("job_id")
        val timeout = JSONObject(jobs.waitFor(id, 10) { false })
        assertTrue(timeout.getBoolean("wait_timed_out"))
        assertEquals("CANCELLED", JSONObject(jobs.waitFor(id, 1000) { true }).getString("code"))
        assertTrue(JSONObject(jobs.read(id)).getBoolean("running"))
        jobs.stop(id)
    }

    @Test fun incrementalOffsetsAndConsumerCursorDoNotChangeLegacyTailReads() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val gate = File(temporary.root, "continue")
        val id = JSONObject(jobs.start(supervisor,
            "printf '你😀'; while [ ! -f ${shellQuote(gate.path)} ]; do sleep 0.02; done; printf '好'",
            temporary.root.path, "user", TerminalEnvironment.ANDROID, "增量输出", 5000)).getString("job_id")
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        var first: JSONObject
        do {
            first = JSONObject(jobs.read(id, fromOffset = 0))
            if (first.getString("output") == "你😀") break
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertEquals("你😀", first.getString("output"))
        assertEquals(7L, first.getLong("next_offset"))
        assertFalse(first.getBoolean("lossy"))
        assertEquals("你😀", JSONObject(jobs.read(id, fromOffset = 0)).getString("output"))
        assertEquals("你😀", JSONObject(jobs.read(id, incremental = true)).getString("output"))
        assertEquals("", JSONObject(jobs.read(id, incremental = true)).getString("output"))
        gate.writeText("go")
        jobs.waitFor(id, 5000) { false }
        val second = JSONObject(jobs.read(id, fromOffset = first.getLong("next_offset")))
        assertEquals("好", second.getString("output"))
        assertEquals(10L, second.getLong("next_offset"))
        assertEquals(10L, second.getLong("total_bytes"))
        assertEquals("好", JSONObject(jobs.read(id, incremental = true)).getString("output"))
        val legacy = JSONObject(jobs.read(id))
        assertEquals("你😀好", legacy.getString("output"))
        assertFalse(legacy.has("next_offset"))
        assertFalse(legacy.getBoolean("output_truncated"))
    }

    @Test fun incrementalReadsPageOnUtf8BoundariesAndReportEviction() {
        val supervisor = supervisor()
        val jobs = host(supervisor)
        val id = JSONObject(jobs.start(supervisor,
            "head -c 1023 /dev/zero | tr '\\000' x; printf '😀end'", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "分页", 5000)).getString("job_id")
        jobs.waitFor(id, 5000) { false }
        val first = JSONObject(jobs.read(id, 1024, fromOffset = 0))
        assertEquals(1023, first.getString("output").length)
        assertEquals(1023L, first.getLong("next_offset"))
        val second = JSONObject(jobs.read(id, 1024, fromOffset = first.getLong("next_offset")))
        assertEquals("😀end", second.getString("output"))
        assertFalse(second.getBoolean("lossy"))
        val large = JSONObject(jobs.start(supervisor,
            "head -c 300000 /dev/zero | tr '\\000' x", temporary.root.path,
            "user", TerminalEnvironment.ANDROID, "环淘汰", 5000)).getString("job_id")
        jobs.waitFor(large, 5000) { false }
        val read = JSONObject(jobs.read(large, fromOffset = 0))
        assertTrue(read.getBoolean("lossy"))
        assertEquals(300000L, read.getLong("total_bytes"))
        assertEquals(300000L - 256 * 1024 + 16000, read.getLong("next_offset"))
        assertEquals(300000L, File(read.getString("output_path")).length())
    }

    @Test fun completionCallbackNotifiesBeforeThePeriodicMonitorTick() {
        val launcher = supervisor()
        val completed = java.util.concurrent.CountDownLatch(1)
        val notified = java.util.concurrent.CountDownLatch(1)
        val completionTime = java.util.concurrent.atomic.AtomicLong()
        val notificationTime = java.util.concurrent.atomic.AtomicLong()
        val removeListener = launcher.onCommandCompleted {
            completionTime.set(System.nanoTime())
            completed.countDown()
        }
        // UI 与 Agent 使用不同 supervisor 实例，也必须收到同一任务的完成事件。
        val observer = supervisor()
        val jobs = BackgroundTerminalJobs(observer, NoopLogger, onCompleted = {
            notificationTime.set(System.nanoTime())
            notified.countDown()
        }).also { hosts += it }
        val gate = File(temporary.root, "finish")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val id = JSONObject(jobs.start(launcher,
                "while [ ! -f ${shellQuote(gate.path)} ]; do sleep 0.01; done; printf done",
                temporary.root.path, "user", TerminalEnvironment.ANDROID, "即时完成", 5000)).getString("job_id")
            val waiting = java.util.concurrent.CountDownLatch(1)
            val waiter = executor.submit<DetachedTask?> {
                waiting.countDown()
                jobs.awaitCompletion(id, 5000) { false }
            }
            assertTrue(waiting.await(1, java.util.concurrent.TimeUnit.SECONDS))
            gate.writeText("go")
            // 从实际 process.waitFor 完成开始计时，避免将系统进程组清理时间计入通知延迟。
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue("completion must notify without waiting for the periodic tick",
                notified.await(200, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertTrue(notificationTime.get() - completionTime.get() < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200))
            assertTrue(waiter.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)!!.completedAt != null)
            assertEquals("completed", JSONObject(jobs.waitFor(id, 1000) { false }).getString("status"))
        } finally { executor.shutdownNow(); removeListener() }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
