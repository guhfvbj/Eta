package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 与 POSIX 集成用例并存：在 Windows 上也执行真实的升级/派发/消费状态机。 */
class BackgroundTerminalJobsLogicTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hosts = mutableListOf<BackgroundTerminalJobs>()
    private val notifications = AtomicInteger()
    private val sinkCalls = AtomicInteger()

    private fun supervisor(launcher: ControlledCommandLauncher) = DetachedTaskSupervisor(
        NoopLogger, File(temporary.root, "tasks.json"), daemonDir = temporary.root.path,
        rootAvailable = { false }, commandLauncher = launcher,
    )
    private fun host(supervisor: DetachedTaskSupervisor) = BackgroundTerminalJobs(supervisor, NoopLogger,
        onCompleted = { notifications.incrementAndGet() }).also { jobs ->
        hosts += jobs
        jobs.completionSink = object : JobCompletionSink {
            override fun onJobCompleted(task: DetachedTask): Boolean { sinkCalls.incrementAndGet(); return true }
        }
    }
    private fun controller(supervisor: DetachedTaskSupervisor, jobs: BackgroundTerminalJobs) = RootShellTerminalController(
        NoopLogger, detachedSupervisor = supervisor, backgroundJobs = jobs, rootAvailable = { false },
        executionContext = TerminalExecutionContext(TerminalEnvironment.DEBIAN, null, temporary.root.path),
        origin = JobOrigin("conversation", "origin-run"),
    )
    private fun command(timeout: Int) = JSONObject().put("command", "controlled command")
        .put("cwd", temporary.root.path).put("promote_on_timeout", true).put("timeout_ms", timeout)

    @Test fun promotableForegroundCompletionRemainsInlineAndSilent() {
        val launcher = ControlledCommandLauncher(completeImmediately = true)
        val supervisor = supervisor(launcher)
        val jobs = host(supervisor)
        val controller = controller(supervisor, jobs)
        try {
            val result = JSONObject(controller.executeTool("terminal", command(5_000)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("done", result.getString("stdout"))
            val task = supervisor.list().single().task
            assertEquals(JobDelivery.INLINE, task.delivery)
            assertEquals("conversation", task.conversationId)
            assertEquals("origin-run", task.originRunId)
            jobs.list(false)
            assertEquals(0, sinkCalls.get())
            assertEquals(0, notifications.get())
        } finally { controller.closeAll() }
    }

    @Test fun promotionPreservesOwnershipAndChoosesWakeupOrQuiet() {
        for (delivery in listOf(JobDelivery.WAKEUP, JobDelivery.QUIET)) {
            val launcher = ControlledCommandLauncher()
            val supervisor = supervisor(launcher)
            val jobs = host(supervisor)
            val controller = controller(supervisor, jobs)
            try {
                val result = JSONObject(controller.executeTool("terminal", command(1)
                    .put("background_delivery", delivery.wireName)))
                assertEquals("promoted", result.getString("status"))
                val task = supervisor.findTask(result.getString("job_id"))!!
                assertEquals(delivery, task.delivery)
                assertEquals("conversation", task.conversationId)
                assertEquals("origin-run", task.originRunId)
                controller.closeAll()
                assertEquals(null, supervisor.findTask(task.id)!!.completedAt)
                assertEquals(1L, launcher.terminated.count)
                jobs.stop(task.id)
                assertTrue(supervisor.findTask(task.id)!!.consumedAt != null)
            } finally { launcher.complete(); controller.closeAll(); jobs.close() }
        }
        assertEquals(0, sinkCalls.get())
        assertEquals(0, notifications.get())
    }

    @Test fun completionIsDispatchedBeforeForegroundLeaseRelease() {
        val launcher = ControlledCommandLauncher()
        val supervisor = supervisor(launcher)
        val leased = AtomicBoolean()
        val released = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val jobs = BackgroundTerminalJobs(supervisor, NoopLogger,
            acquireLease = { leased.set(true); true },
            releaseLease = { events += "release"; leased.set(false); released.countDown() },
            onCompleted = { notifications.incrementAndGet() }).also { hosts += it }
        jobs.completionSink = object : JobCompletionSink {
            override fun onJobCompleted(task: DetachedTask): Boolean {
                assertTrue(leased.get())
                events += "sink"
                return true
            }
        }
        val id = JSONObject(jobs.start(supervisor, "controlled", temporary.root.path, "user",
            TerminalEnvironment.ANDROID, "lease", 0, origin = JobOrigin("c", "r"))).getString("job_id")
        launcher.complete()
        assertTrue(released.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("sink", "release"), events.toList())
        assertTrue(supervisor.findTask(id)!!.deliveryAttemptedAt != null)
        assertEquals(0, notifications.get())
    }

    @Test fun stoppingRunningCommandConsumesTheResultBeforeAutomaticDelivery() {
        val launcher = ControlledCommandLauncher()
        val supervisor = supervisor(launcher)
        val jobs = host(supervisor)
        val id = JSONObject(jobs.start(supervisor, "controlled", temporary.root.path, "user",
            TerminalEnvironment.ANDROID, "stop", 0, origin = JobOrigin("c", "r"))).getString("job_id")
        launcher.beforeTerminate = { assertTrue(supervisor.findTask(id)!!.consumedAt != null) }
        assertEquals("stopped", JSONObject(jobs.stop(id)).getString("status"))
        jobs.list(false)
        assertEquals(0, sinkCalls.get())
        assertEquals(0, notifications.get())
        assertTrue(supervisor.findTask(id)!!.consumedAt != null)
    }

    @Test fun cancellingPromotableForegroundCommandKeepsInlineDelivery() {
        val launcher = ControlledCommandLauncher()
        val supervisor = supervisor(launcher)
        val jobs = host(supervisor)
        val controller = controller(supervisor, jobs)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<String> { controller.executeTool("terminal", command(30_000)) }
            assertTrue(launcher.started.await(5, TimeUnit.SECONDS))
            assertTrue(launcher.waiting.await(5, TimeUnit.SECONDS))
            controller.interruptAll()
            result.get(5, TimeUnit.SECONDS)
            jobs.list(false)
            val task = supervisor.list().single().task
            assertEquals(JobDelivery.INLINE, task.delivery)
            assertTrue(task.completedAt != null)
            assertEquals(0, sinkCalls.get())
            assertEquals(0, notifications.get())
        } finally { launcher.complete(); controller.closeAll(); worker.shutdownNow() }
    }

    @Test fun rootCommandCompletesWithRootUnavailableWhenRootIsLost() {
        val file = File(temporary.root, "tasks.json")
        val runningRootTask = DetachedTask(
            id = "job_root_lost",
            pid = 4242,
            token = "token",
            command = "sleep 30",
            cwd = temporary.root.path,
            identity = "root",
            environment = TerminalEnvironment.ANDROID,
            logPath = File(temporary.root, "job_root_lost.log").path,
            startedAt = 1L,
            commandTask = true,
            conversationId = "conversation",
            originRunId = "origin-run",
            delivery = JobDelivery.WAKEUP,
        )
        file.writeText(org.json.JSONArray().put(runningRootTask.toRecordJson()).toString())
        val supervisor = DetachedTaskSupervisor(
            NoopLogger, file, daemonDir = temporary.root.path, rootAvailable = { false },
        )
        val reasons = mutableListOf<String?>()
        val jobs = BackgroundTerminalJobs(
            supervisor,
            NoopLogger,
            onCompleted = { _, note -> reasons += note; notifications.incrementAndGet() },
        ).also { hosts += it }
        jobs.completionSink = object : JobCompletionSink {
            override fun onJobCompleted(task: DetachedTask) = false
            override fun onJobCompletedWithReason(task: DetachedTask) =
                JobCompletionOutcome.Fallback("已连续唤醒 3 次，本次仅通知")
        }
        val listed = JSONObject(jobs.list(false)).getJSONArray("tasks").getJSONObject(0)
        assertEquals("root_unavailable", listed.getString("status"))
        assertFalse(listed.getBoolean("running"))
        assertEquals("root_unavailable", supervisor.findTask("job_root_lost")!!.endReason)
        assertEquals(1, notifications.get())
        assertEquals(listOf("已连续唤醒 3 次，本次仅通知"), reasons)
    }

    @After fun closeHosts() { hosts.forEach { it.close() } }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
