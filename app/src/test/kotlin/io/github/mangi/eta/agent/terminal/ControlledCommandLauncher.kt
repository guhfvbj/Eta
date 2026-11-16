package io.github.mangi.eta.agent.terminal

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 不启动 OS 进程；真实 supervisor 仍负责记录、输出排空与完成事件。 */
internal class ControlledCommandLauncher(private val completeImmediately: Boolean = false) : DetachedTaskSupervisor.CommandLauncher {
    val started = CountDownLatch(1)
    val terminated = CountDownLatch(1)
    val waiting = CountDownLatch(1)
    @Volatile var beforeTerminate: () -> Unit = {}
    private val finished = CountDownLatch(1)
    @Volatile private var exitCode = 0

    fun complete(code: Int = 0) { exitCode = code; finished.countDown() }

    override fun start(request: DetachedTaskSupervisor.CommandLaunch): DetachedTaskSupervisor.CommandHandle {
        // 无 capture 时真实 shell 把输出重定向至日志；假体也建立该文件，确保 readLogs
        // 始终走本地读取而不会因文件缺失退回 sh。capture 路径由真实 reader 写入。
        if (request.mergeStderr) File(request.logPath).apply {
            parentFile?.mkdirs()
            writeText("done")
        }
        val process = object : Process() {
            override fun getInputStream() = ByteArrayInputStream("done".toByteArray())
            override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
            override fun getOutputStream() = ByteArrayOutputStream()
            override fun waitFor(): Int { waiting.countDown(); finished.await(); return exitCode }
            override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = finished.await(timeout, unit)
            override fun exitValue(): Int {
                check(finished.count == 0L) { "still running" }
                return exitCode
            }
            override fun isAlive(): Boolean = finished.count != 0L
            override fun destroy() { beforeTerminate(); complete(); terminated.countDown() }
        }
        if (completeImmediately) complete()
        started.countDown()
        return object : DetachedTaskSupervisor.CommandHandle {
            override val process: Process = process
            override val ownership = ShellProcessOwnership(Long.MAX_VALUE, "controlled", false)
            override fun terminate() = process.destroy()
            override fun retire() = Unit
        }
    }
}
