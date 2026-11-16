package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

/** App 持有后台命令；Agent run 只提交和查询，结束回复不会释放任务。 */
internal class BackgroundTerminalJobs(
    private val supervisor: DetachedTaskSupervisor,
    private val logger: AgentLogger,
    private val acquireLease: (() -> Unit) -> Boolean = { true },
    private val releaseLease: () -> Unit = {},
    private val onCompleted: (DetachedTask) -> Unit = {},
    private val completionNotificationsEnabled: () -> Boolean = { true },
) : AutoCloseable {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "eta-background-commands").apply { isDaemon = true }
    }
    private val waitLock = ReentrantLock()
    private val changed = waitLock.newCondition()
    private var monitor: ScheduledFuture<*>? = null
    private var leased = false
    @Volatile private var closed = false
    @Volatile var completionSink: JobCompletionSink? = null
    private val refreshStateLock = Any()
    private var refreshing = false
    private var refreshAgain = false
    private class OutputView(var ring: JobOutputRing = JobOutputRing(), val cursor: JobOutputRing.Cursor = JobOutputRing.Cursor())
    private val outputViews = mutableMapOf<String, OutputView>()
    private val removeCompletionListener = supervisor.onCommandCompleted {
        // waiter 已提交完成记录；立即叫醒等待者，并在串行监控线程发送完成通知。
        waitLock.withLock { changed.signalAll() }
        runCatching { executor.execute(::refresh) }
    }

    @Synchronized
    fun start(
        launcher: DetachedTaskSupervisor,
        command: String,
        cwd: String,
        identity: String,
        environment: TerminalEnvironment,
        description: String,
        timeoutMs: Long,
        outputCapture: ShellOutputSpill? = null,
        origin: JobOrigin? = null,
    ): String {
        if (closed) return failure("BACKGROUND_CLOSED", "后台命令宿主已关闭")
        if (!lease()) return failure("BACKGROUND_START_NOT_ALLOWED", "系统未允许启动后台执行，请返回 Eta 后重试")
        return when (val result = launcher.start(command, cwd, identity, environment,
            commandTask = true, description = description, timeoutMs = timeoutMs.coerceAtLeast(0), outputCapture = outputCapture, origin = origin)) {
            is DaemonStartResult.Failed -> {
                if (monitor == null) release()
                failure(result.code, result.message)
            }
            is DaemonStartResult.Started -> {
                monitor()
                taskJson(result.task, includeOutputTail = false).put("action", "start").toString()
            }
        }
    }

    fun list(activeOnly: Boolean): String {
        refresh()
        val tasks = supervisor.list().filter { it.task.commandTask && (!activeOnly || it.task.completedAt == null) }
        return base("list").put("tasks", JSONArray(tasks.map { taskJson(it.task) })).toString()
    }

    fun read(id: String, maxBytes: Int = 16_000, fromOffset: Long? = null, incremental: Boolean = false): String {
        val task = supervisor.findTask(id)?.takeIf { it.commandTask }
            ?: return failure("JOB_NOT_FOUND", "未找到后台命令，请用 terminal_job 的 list 查看任务")
        val result = taskJson(task, includeOutputTail = false).put("action", "read")
        if (fromOffset != null || incremental) {
            val delta = synchronized(this) {
                val view = updateOutput(task) ?: return failure("LOGS_UNAVAILABLE", "后台命令日志不可用")
                if (fromOffset != null) view.ring.readFrom(fromOffset.coerceAtLeast(0), maxBytes.coerceIn(1_024, DetachedTaskSupervisor.MAX_LOG_READ_BYTES))
                else view.ring.consumeFrom(view.cursor, maxBytes.coerceIn(1_024, DetachedTaskSupervisor.MAX_LOG_READ_BYTES))
            }
            if (supervisor.findTask(id)?.completedAt != null) supervisor.markConsumed(id)
            result.put("consumed", supervisor.findTask(id)?.consumedAt != null)
            return result.put("output", delta.text).put("output_truncated", delta.lossy || delta.nextOffset < delta.totalBytes)
                .put("next_offset", delta.nextOffset).put("total_bytes", delta.totalBytes).put("lossy", delta.lossy).toString()
        }
        val logs = supervisor.readLogs(id, maxBytes)
        if (!logs.ok) return result.put("ok", false).put("code", logs.code).put("message", logs.message).toString()
        if (supervisor.findTask(id)?.completedAt != null) supervisor.markConsumed(id)
        result.put("consumed", supervisor.findTask(id)?.consumedAt != null)
        return result.put("output", logs.text).put("output_truncated", logs.truncated).toString()
    }

    fun stop(id: String): String {
        val stopped = synchronized(this) {
            val task = supervisor.findTask(id)?.takeIf { it.commandTask }
                ?: return failure("JOB_NOT_FOUND", "未找到后台命令")
            if (!supervisor.stopCommand(task.id, consume = true)) return failure("JOB_STOP_FAILED", "无法停止后台命令，请检查当前身份授权")
            supervisor.markConsumed(id)
            supervisor.findTask(id) ?: task
        }
        refresh()
        return taskJson(stopped).put("action", "stop").toString()
    }

    fun waitFor(id: String, timeoutMs: Long, isCancelled: () -> Boolean): String {
        val initial = supervisor.findTask(id)?.takeIf { it.commandTask }
            ?: return failure("JOB_NOT_FOUND", "未找到后台命令")
        if (isCancelled()) return failure("CANCELLED", "已取消等待；后台命令仍保留，可单独停止")
        if (initial.completedAt != null) supervisor.markConsumed(id)
        resume()
        val task = awaitCompletion(id, timeoutMs.coerceIn(0, 60_000), isCancelled)
            ?: return failure("JOB_NOT_FOUND", "后台命令已清理")
        if (isCancelled()) return failure("CANCELLED", "已取消等待；后台命令仍保留，可单独停止")
        if (supervisor.findTask(id)?.completedAt != null) supervisor.markConsumed(id)
        refresh()
        return taskJson(supervisor.findTask(id) ?: task).put("action", "wait").put("wait_timed_out", task.completedAt == null).toString()
    }

    /** 前台 promote 的等待可达 180 秒，独立于 terminal_job.wait 的 60 秒上限。 */
    fun awaitCompletion(id: String, timeoutMs: Long, isCancelled: () -> Boolean): DetachedTask? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        waitLock.withLock {
            while (supervisor.findTask(id)?.completedAt == null) {
                if (isCancelled() || closed || supervisor.findTask(id) == null) break
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) break
                changed.awaitNanos(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(200)))
            }
        }
        return supervisor.findTask(id)
    }

    @Synchronized
    private fun updateOutput(task: DetachedTask): OutputView? {
        val view = outputViews.getOrPut(task.id) { OutputView() }
        val window = supervisor.readLogWindow(task, view.ring.total) ?: return null
        if (window.at != view.ring.total) view.ring = JobOutputRing(initialOffset = window.at)
        view.ring.append(window.bytes)
        return view
    }

    /** 重新打开 App 后从同一份任务记录恢复观察，不重新执行命令。 */
    fun resume() {
        if (closed) return
        refresh()
        synchronized(this) {
            if (!closed && supervisor.list().any { it.task.commandTask && it.task.completedAt == null } && lease()) monitor()
        }
    }

    fun markConsumed(id: String) = supervisor.markConsumed(id)
    fun markDeliveryAttempted(id: String) = supervisor.markDeliveryAttempted(id)
    fun setDelivery(id: String, delivery: JobDelivery) {
        synchronized(this) {
            if (closed) return
            // 超时边界恰好完成时，INLINE 巡检可能已释放租约；升级先重新取得接力引用。
            if (delivery != JobDelivery.INLINE && supervisor.findTask(id) != null) lease()
            supervisor.setDelivery(id, delivery)
        }
        runCatching { executor.execute(::refresh) }
    }

    private fun refresh() {
        synchronized(refreshStateLock) {
            if (closed) return
            if (refreshing) { refreshAgain = true; return }
            refreshing = true
        }
        try {
            val pending = synchronized(this) {
                var tasks = supervisor.list().filter { it.task.commandTask }
                val now = System.currentTimeMillis()
                val expired = tasks.filter { it.running && it.task.timeoutMs > 0 && now - it.task.startedAt >= it.task.timeoutMs }
                expired.forEach { supervisor.stopCommand(it.task.id, "timed_out") }
                if (expired.isNotEmpty()) tasks = supervisor.list().filter { it.task.commandTask }
                outputViews.keys.retainAll(tasks.mapTo(mutableSetOf()) { it.task.id })
                tasks.filter { it.task.id in outputViews }.forEach { updateOutput(it.task) }
                tasks.map { it.task }.filter { it.completedAt != null && !it.completionNotified &&
                    it.delivery != JobDelivery.INLINE && it.consumedAt == null && it.deliveryAttemptedAt == null }
            }
            // 不持记录锁或宿主锁调用 Runtime；主线程可能正在提交新的终端命令。
            pending.forEach { candidate ->
                val task = supervisor.findTask(candidate.id) ?: return@forEach
                if (task.consumedAt != null || task.deliveryAttemptedAt != null || task.completionNotified ||
                    task.delivery == JobDelivery.INLINE) return@forEach
                try {
                    val handled = runCatching { completionSink?.onJobCompleted(task) == true }.getOrDefault(false)
                    if (handled) supervisor.markDeliveryAttempted(task.id) else onCompleted(task)
                    supervisor.markCompletionNotified(task.id)
                } catch (failure: Exception) {
                    logger.warn("后台命令完成派发失败：type=${failure.javaClass.simpleName}")
                }
            }
            waitLock.withLock { changed.signalAll() }
            synchronized(this) {
                // 必须先派发再释放 terminal-background-commands 租约；唤醒在上面接管 run:<id> 租约，避免前台服务出现空窗。
                val latest = supervisor.list().filter { it.task.commandTask }
                val collectedIds = pending.mapTo(mutableSetOf()) { it.id }
                val hasNewPending = latest.any { status ->
                    val task = status.task
                    task.id !in collectedIds && task.completedAt != null && !task.completionNotified &&
                        task.delivery != JobDelivery.INLINE && task.consumedAt == null && task.deliveryAttemptedAt == null
                }
                if (hasNewPending) synchronized(refreshStateLock) { refreshAgain = true }
                if (latest.none { it.running } && !hasNewPending) {
                    monitor?.cancel(false)
                    monitor = null
                    release()
                }
            }
        } catch (failure: Exception) {
            logger.warn("后台命令刷新失败：type=${failure.javaClass.simpleName}")
        } finally {
            synchronized(refreshStateLock) {
                refreshing = false
                if (refreshAgain && !closed) {
                    refreshAgain = false
                    runCatching { executor.execute(::refresh) }
                }
            }
        }
    }

    private fun monitor() {
        if (monitor == null) monitor = executor.scheduleWithFixedDelay(::refresh, 0, 500, TimeUnit.MILLISECONDS)
    }

    private fun lease(): Boolean {
        if (!leased) leased = acquireLease { stopAll() }
        return leased
    }

    private fun release() {
        if (leased) {
            leased = false
            releaseLease()
        }
    }

    fun stopAll() {
        synchronized(this) {
            supervisor.list().filter { it.task.commandTask && it.running }.forEach { supervisor.stopCommand(it.task.id) }
        }
        refresh()
    }

    override fun close() {
        stopAll()
        synchronized(this) {
            closed = true
            removeCompletionListener()
            outputViews.clear()
            monitor?.cancel(false)
            monitor = null
            executor.shutdownNow()
            release()
        }
        waitLock.withLock { changed.signalAll() }
    }

    private fun taskJson(task: DetachedTask, includeOutputTail: Boolean = true): JSONObject = base("status")
        .put("job_id", task.id).put("description", task.description)
        .put("environment", task.environment.wireName).put("identity", task.identity).put("cwd", task.cwd)
        .put("status", task.commandStatus).put("running", task.completedAt == null)
        .put("started_at", task.startedAt).put("completed_at", task.completedAt ?: JSONObject.NULL)
        .put("elapsed_ms", ((task.completedAt ?: System.currentTimeMillis()) - task.startedAt).coerceAtLeast(0L))
        .put("exit_code", task.exitCode ?: JSONObject.NULL).put("end_reason", task.endReason ?: JSONObject.NULL)
        .put("output_path", supervisor.hostDaemonPath(task, task.logPath)).put("output_environment", "android")
        .put("timeout_ms", task.timeoutMs)
        .put("delivery", task.delivery.wireName)
        .put("consumed", task.consumedAt != null)
        .put("wake_attempted", task.deliveryAttemptedAt != null)
        .put("completion_notifications_enabled", completionNotificationsEnabled())
        .also { result ->
            if (includeOutputTail) {
                val logs = supervisor.readLogs(task.id, 1_024)
                if (logs.ok) result.put("output_tail", logs.text)
            }
        }

    private fun base(action: String) = JSONObject().put("ok", true).put("tool", "terminal_job").put("action", action)
    private fun failure(code: String, message: String) = JSONObject().put("ok", false).put("tool", "terminal_job")
        .put("code", code).put("message", message).toString()
}
