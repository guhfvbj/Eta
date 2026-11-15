package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootShellTerminalControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test fun sessionEvalHandlesMultilineHeredocEmptyStdinAndKeepsCwd() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        val directory = temporaryFolder.newFolder("child")
        try {
            val opened = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "open").put("cwd", temporaryFolder.root.path)))
            assertTrue(opened.toString(), opened.getBoolean("ok"))
            assertTrue(opened.getJSONObject("runtime").getInt("uid") >= 0)
            val id = opened.getString("session_id")
            val result = JSONObject(controller.executeTool("terminal", JSONObject().put("session_id", id)
                .put("command", "cd child\ncat </dev/null\ncat <<'EOF'\nline 'one'\nline two\nEOF\nexport ETA_MULTILINE=kept\n# trailing comment")))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("line 'one'\nline two", result.getString("stdout"))
            assertEquals(directory.canonicalPath, result.getString("cwd"))
            val next = JSONObject(controller.executeTool("terminal", JSONObject().put("session_id", id)
                .put("command", "printf '%s:%s:%s' \"\$ETA_MULTILINE\" \"\$TERM\" \"\$NO_COLOR\"")))
            assertEquals("kept:dumb:1", next.getString("stdout"))
            assertEquals(directory.canonicalPath, next.getString("cwd"))
        } finally { controller.closeAll() }
    }

    @Test fun sessionWaitsForCompleteStatusWithoutLeadingNewline() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        val directory = temporaryFolder.newFolder("split-status")
        try {
            val id = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "open").put("cwd", temporaryFolder.root.path))).getString("session_id")
            // Override only the protocol's printf: remove its initial newline and delay cwd.
            val command = "printf() { format=\${1#??}; command printf \"\${format%??}\" \"\$2\" ''; " +
                "sleep 0.08; command printf '%s\\n' \"\$3\"; }; cd split-status"
            val result = JSONObject(controller.executeTool("terminal", JSONObject().put("session_id", id).put("command", command)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("", result.getString("stdout"))
            assertEquals(directory.canonicalPath, result.getString("cwd"))
            val next = JSONObject(controller.executeTool("terminal", JSONObject().put("session_id", id).put("command", "pwd")))
            assertEquals(directory.canonicalPath, next.getString("stdout"))
            assertEquals(directory.canonicalPath, next.getString("cwd"))
        } finally { controller.closeAll() }
    }

    @Test fun sessionDrainsStderrTailAndReportsBoundedOutputTruncation() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val id = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "open").put("cwd", temporaryFolder.root.path))).getString("session_id")
            repeat(6) { index ->
                val result = JSONObject(controller.executeTool("terminal", JSONObject().put("session_id", id)
                    .put("command", "cat </dev/null; printf tail-$index >&2")))
                assertTrue(result.toString(), result.getBoolean("ok"))
                assertEquals("tail-$index", result.getString("stderr"))
            }
            val result = JSONObject(controller.executeTool("terminal", JSONObject().put("session_id", id)
                .put("command", "head -c 1100000 /dev/zero | tr '\\000' x")))
            assertTrue(result.toString(), result.getBoolean("stdout_truncated"))
            assertTrue(result.getBoolean("output_truncated"))
            assertTrue(result.getBoolean("session_closed"))
            assertFalse(result.getBoolean("timed_out"))
            assertTrue(result.getString("stdout").startsWith("xxxx"))
        } finally { controller.closeAll() }
    }

    @Test
    fun sessionExecKeepsShellEnvironment() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val open = JSONObject(
                controller.executeTool("terminal", JSONObject().put("action", "open")
                    .put("cwd", temporaryFolder.root.absolutePath)
                    .put("identity", "user")
                )
            )
            val sessionId = open.getString("session_id")

            val export = JSONObject(
                controller.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "export ETA_TEST_VALUE=streaming")
                    .put("timeout_ms", 5_000)
                    .put("identity", "user")
                    .put("merge_stderr", false)
                    .put("session_id", sessionId)
                )
            )
            val echo = JSONObject(
                controller.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "printf %s \"\$ETA_TEST_VALUE\"")
                    .put("timeout_ms", 5_000)
                    .put("identity", "user")
                    .put("merge_stderr", false)
                    .put("session_id", sessionId)
                )
            )

            assertTrue(export.toString(), export.getBoolean("ok"))
            assertTrue(echo.toString(), echo.getBoolean("ok"))
            assertEquals("streaming", echo.getString("stdout"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun stoppingBackgroundJobDoesNotCloseTerminalSession() {
        val supervisor = DetachedTaskSupervisor(NoopLogger, File(temporaryFolder.root, "jobs.json"),
            daemonDir = File(temporaryFolder.root, "logs").path, rootAvailable = { false })
        val jobs = BackgroundTerminalJobs(supervisor, NoopLogger)
        val controller = RootShellTerminalController(NoopLogger, detachedSupervisor = supervisor, backgroundJobs = jobs, rootAvailable = { false })
        try {
            val open = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "open").put("cwd", temporaryFolder.root.path)))
            val sessionId = open.getString("session_id")
            val started = JSONObject(controller.executeTool("terminal_job", JSONObject()
                .put("action", "start").put("command", "sleep 30")
                .put("cwd", temporaryFolder.root.path)))
            assertTrue(started.toString(), started.getBoolean("ok"))
            assertEquals("terminal_job", started.getString("tool"))
            assertEquals("start", started.getString("action"))
            val jobId = started.getString("job_id")

            val stopped = JSONObject(controller.executeTool("terminal_job", JSONObject()
                .put("action", "stop").put("job_id", jobId)))
            assertEquals("stopped", stopped.getString("status"))
            assertEquals("stop", stopped.getString("action"))
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("command", "printf session_alive").put("session_id", sessionId)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("session_alive", result.getString("stdout"))
            val removed = JSONObject(controller.executeTool("terminal_job", JSONObject()
                .put("action", "read").put("job_id", jobId)))
            assertEquals("stopped", removed.getString("status"))
        } finally {
            controller.closeAll()
            jobs.close()
        }
    }

    @Test
    fun terminalLogsDoNotContainCommandOrWorkingDirectory() {
        val logger = RecordingLogger()
        val controller = RootShellTerminalController(logger)
        val cwd = temporaryFolder.newFolder("sensitive_cwd_marker").absolutePath
        val command = "printf sensitive_command_marker"

        try {
            val result = JSONObject(
                controller.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", command)
                    .put("cwd", cwd)
                    .put("timeout_ms", 5_000)
                    .put("identity", "user")
                    .put("merge_stderr", false)
                )
            )

            assertTrue(result.toString(), result.getBoolean("ok"))
            val logs = logger.messages.joinToString("\n")
            assertTrue(logs, logs.contains("action=exec"))
            assertTrue(logs, logs.contains("commandChars=${command.length}"))
            assertFalse(logs, logs.contains("sensitive_command_marker"))
            assertFalse(logs, logs.contains("sensitive_cwd_marker"))
            assertFalse(logs, logs.contains(cwd))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun terminalExecAcceptsExtraFieldsAndUsesExecutionDefaults() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "EXEC").put("command", "printf result")
                .put("identity", "USER").put("environment", "ANDROID")
                .put("cwd", temporaryFolder.root.path).put("timeout_ms", "5000")
                .put("unused", true)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("result", result.getString("stdout"))
            assertEquals("user", result.getString("identity"))
        } finally { controller.closeAll() }
    }

    @Test
    fun sessionExecUsesStoredEnvironmentDespiteRedundantArguments() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        try {
            val open = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "open").put("identity", "user").put("cwd", temporaryFolder.root.path)))
            assertTrue(open.toString(), open.getBoolean("ok"))
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "exec").put("command", "pwd")
                .put("session_id", open.getString("session_id"))
                .put("cwd", "/missing").put("identity", "root").put("environment", "linux")))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals(temporaryFolder.root.canonicalPath, result.getString("stdout").trim())
            assertEquals("user", result.getString("identity"))
        } finally { controller.closeAll() }
    }

    @Test
    fun longCommandsCanWriteFiles() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false })
        val content = "a".repeat(20_000)
        try {
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "exec").put("cwd", temporaryFolder.root.path)
                .put("command", "cat > note.txt <<'EOF'\n$content\nEOF")))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("$content\n", File(temporaryFolder.root, "note.txt").readText())
        } finally { controller.closeAll() }
    }

    @Test
    fun oneShotReturnsObservedRuntimeWithoutMixingMetadataIntoOutput() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val args = JSONObject().put("action", "exec").put("command", "printf visible_output")
                .put("identity", "user").put("cwd", temporaryFolder.root.absolutePath)
            val result = JSONObject(controller.executeTool("terminal", args))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("visible_output", result.getString("stdout"))
            assertEquals("exited", result.getString("status"))
            assertEquals("exec", result.getString("action"))
            val runtime = result.getJSONObject("runtime")
            assertEquals("user", runtime.getString("host_identity"))
            assertTrue(runtime.toString(), runtime.getInt("uid") >= 0)
            assertFalse(runtime.getString("shell_provider").isBlank())
            assertTrue(runtime.getJSONArray("available_commands").toString().contains("sh"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun outputJustOverDisplayLimitIsMarkedTruncated() {
        val controller = RootShellTerminalController(NoopLogger)
        try {
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "exec")
                .put("command", "awk 'BEGIN { for (i=0;i<16001;i++) printf \"x\" }'")
                .put("identity", "user")
                .put("cwd", temporaryFolder.root.absolutePath)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertTrue(result.toString(), result.getBoolean("stdout_truncated"))
            assertFalse(result.getString("stdout").contains("__ETA_RUNTIME_"))
        } finally {
            controller.closeAll()
        }
    }

    @Test
    fun runtimeProbeHandlesPartialHeaderAndDistinguishesGuestUid() {
        val marker = TerminalExecutionProbe.marker()
        assertEquals("", TerminalExecutionProbe.extract(marker.take(10), marker, "user", TerminalEnvironment.ANDROID).text)
        val text = "$marker:begin\nuid=0\nprovider=sh\ncommand=sh\n$marker:end\nhello"
        val parsed = TerminalExecutionProbe.extract(text, marker, "user", TerminalEnvironment.ALPINE)
        assertEquals("hello", parsed.text)
        assertEquals("guest", parsed.runtime!!.getString("uid_scope"))
        assertEquals("user", parsed.runtime.getString("host_identity"))
    }

    @Test
    fun linuxEnvironmentRequiresInstallationAndRootIdentity() {
        val missingController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPath = File(temporaryFolder.root, "missing-rootfs").absolutePath,
        )
        try {
            val missing = JSONObject(
                missingController.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "python3 --version")
                    .put("cwd", temporaryFolder.root.absolutePath)
                    .put("timeout_ms", 5_000)
                    .put("identity", "root")
                    .put("merge_stderr", false)
                    .put("environment", "linux")
                ),
            )
            assertFalse(missing.toString(), missing.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_NOT_READY", missing.getString("code"))
        } finally {
            missingController.closeAll()
        }

        val rootfs = temporaryFolder.newFolder("ready-rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/busybox").writeText("busybox")
        File(rootfs, AlpineEnvironmentPaths.READY_MARKER).writeText("version=3.24.1\n")
        val readyController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPath = rootfs.absolutePath,
        )
        try {
            val user = JSONObject(
                readyController.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "id")
                    .put("cwd", temporaryFolder.root.absolutePath)
                    .put("timeout_ms", 5_000)
                    .put("identity", "user")
                    .put("merge_stderr", false)
                    .put("environment", "linux")
                ),
            )
            assertFalse(user.toString(), user.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_REQUIRES_ROOT", user.getString("code"))
        } finally {
            readyController.closeAll()
        }

        var requestedEnvironment: TerminalEnvironment? = null
        val debianController = RootShellTerminalController(
            logger = NoopLogger,
            linuxRootfsPathProvider = { environment ->
                requestedEnvironment = environment
                if (environment == TerminalEnvironment.DEBIAN) {
                    File(temporaryFolder.root, "missing-debian-rootfs").absolutePath
                } else {
                    null
                }
            },
            selectedLinuxEnvironmentProvider = { TerminalEnvironment.DEBIAN },
        )
        try {
            val result = JSONObject(
                debianController.executeTool("terminal", JSONObject().put("action", "exec")
                    .put("command", "python3 --version")
                    .put("timeout_ms", 5_000)
                    .put("identity", "root")
                    .put("merge_stderr", false)
                    .put("environment", "linux")
                ),
            )
            assertFalse(result.toString(), result.getBoolean("ok"))
            assertEquals("LINUX_ENVIRONMENT_NOT_READY", result.getString("code"))
            assertEquals(TerminalEnvironment.DEBIAN, requestedEnvironment)
        } finally {
            debianController.closeAll()
        }
    }

    private fun executionContext(workspace: File = temporaryFolder.root) = TerminalExecutionContext(
        linuxEnvironment = TerminalEnvironment.ALPINE, linuxRootfsPath = null, userWorkspacePath = workspace.path)

    @Test fun oneShotSpillsBothStreamsBeyondMemoryBudgetsWithoutLosingOutput() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false }, executionContext = executionContext())
        try {
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("command", "head -c 300000 /dev/zero | tr '\\000' x; printf '你😀END'; " +
                    "head -c 70000 /dev/zero | tr '\\000' e >&2; printf '错误😀END' >&2")
                .put("cwd", temporaryFolder.root.path)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertTrue(result.getBoolean("stdout_truncated"))
            assertTrue(result.getBoolean("stderr_truncated"))
            assertTrue(result.getBoolean("output_truncated"))
            assertFalse(result.getString("stdout").contains('\uFFFD'))
            assertFalse(result.getString("stderr").contains('\uFFFD'))
            val file = File(result.getString("output_path"))
            assertEquals("android", result.getString("output_environment"))
            assertEquals(File(temporaryFolder.root, "tmp/terminal-output").canonicalPath, file.parentFile.canonicalPath)
            assertEquals("x".repeat(300000) + "你😀END\n[stderr]\n" + "e".repeat(70000) + "错误😀END", file.readText())
            assertFalse(file.readText().contains("__ETA_RUNTIME_"))
            controller.closeAll()
            assertTrue("published output remains available after run closes", file.exists())
            assertEquals(listOf(file.name), file.parentFile.listFiles()!!.map { it.name })
        } finally { controller.closeAll() }
    }

    @Test fun jsonPreviewNeverSplitsSurrogatePairsAndSmallOutputLeavesNoSpill() {
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false }, executionContext = executionContext())
        try {
            val small = JSONObject(controller.executeTool("terminal", JSONObject().put("command", "printf '你😀'")))
            assertEquals("你😀", small.getString("stdout"))
            assertFalse(small.has("output_path"))
            assertTrue(File(temporaryFolder.root, "tmp/terminal-output").listFiles()!!.isEmpty())
            for (merge in listOf(false, true)) {
                val result = JSONObject(controller.executeTool("terminal", JSONObject().put("merge_stderr", merge)
                    .put("command", "head -c 15999 /dev/zero | tr '\\000' x; printf '😀tail'; " +
                        "{ head -c 15999 /dev/zero | tr '\\000' e; printf '😀tail'; } >&2")))
                assertTrue(result.getBoolean("output_truncated"))
                listOf(result.getString("stdout"), result.getString("stderr")).forEach { text ->
                    text.forEachIndexed { index, char ->
                        if (char.isHighSurrogate()) assertTrue(text.getOrNull(index + 1)?.isLowSurrogate() == true)
                        if (char.isLowSurrogate()) assertTrue(text.getOrNull(index - 1)?.isHighSurrogate() == true)
                    }
                    assertFalse(text.contains('\uFFFD'))
                }
                assertTrue(File(result.getString("output_path")).readText().contains("😀tail"))
            }
        } finally { controller.closeAll() }
    }

    @Test fun unwritableSpillWorkspaceFallsBackWithoutFailingCommand() {
        val workspace = temporaryFolder.newFile("workspace-is-a-file")
        val controller = RootShellTerminalController(NoopLogger, rootAvailable = { false }, executionContext = executionContext(workspace))
        try {
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("cwd", temporaryFolder.root.path)
                .put("command", "head -c 16001 /dev/zero | tr '\\000' x")))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertTrue(result.getBoolean("stdout_truncated"))
            val path = File(result.getString("output_path"))
            assertTrue(path.exists())
            assertFalse(path.path.startsWith(workspace.path))
        } finally { controller.closeAll() }
    }

    @Test fun spillFilesAndStreamSizeStayBounded() {
        val workspace = temporaryFolder.newFolder("spill-bounds")
        repeat(20) {
            ShellOutputSpill.create(workspace.path)!!.use { spill ->
                spill.append("你😀".toByteArray(), 7, stderr = false)
                assertTrue(spill.retain() != null)
            }
        }
        val files = File(workspace, "tmp/terminal-output").listFiles()!!
        assertEquals(16, files.size)
        assertTrue(files.all { it.extension == "log" && it.readText() == "你😀" })
        ShellOutputSpill.create(workspace.path)!!.use { spill ->
            val bytes = "你".repeat(ShellOutputSpill.MAX_STREAM_BYTES / 3 + 10).toByteArray()
            spill.append(bytes, bytes.size, stderr = false)
            val path = File(spill.retain()!!)
            assertTrue(spill.truncated)
            assertTrue(path.length() <= ShellOutputSpill.MAX_STREAM_BYTES)
            assertFalse(path.readText().contains('\uFFFD'))
        }
    }

    @Test fun liveSpillMergesOnlyCompleteUtf8CharactersAcrossStreams() {
        ShellOutputSpill.create(temporaryFolder.root.path, live = true)!!.use { spill ->
            val chinese = "你".toByteArray()
            val emoji = "😀".toByteArray()
            spill.append(chinese.copyOfRange(0, 1), 1, stderr = false)
            spill.append(emoji, emoji.size, stderr = true)
            spill.append(chinese.copyOfRange(1, 3), 2, stderr = false)
            assertEquals("😀你", spill.logFile.readText())
            val path = File(spill.retain()!!)
            assertEquals("😀你", path.readText())
        }
    }

    @Test fun optedInFastExecReturnsOrdinaryResultIncludingSeparateStderrAndRuntime() {
        withBackgroundController { controller, _ ->
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("command", "printf '你😀'; printf problem >&2").put("promote_on_timeout", true)
                .put("timeout_ms", 3000).put("cwd", temporaryFolder.root.path)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("exec", result.getString("action"))
            assertEquals("exited", result.getString("status"))
            assertEquals(0, result.getInt("exit_code"))
            assertEquals("你😀", result.getString("stdout"))
            assertEquals("problem", result.getString("stderr"))
            assertFalse(result.getBoolean("timed_out"))
            assertFalse(result.optBoolean("promoted"))
            assertTrue(result.getJSONObject("runtime").getInt("uid") >= 0)
            val failed = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("command", "printf failed >&2; exit 7").put("promote_on_timeout", true)
                .put("timeout_ms", 3000).put("cwd", temporaryFolder.root.path)))
            assertFalse(failed.getBoolean("ok"))
            assertEquals("failed", failed.getString("status"))
            assertEquals(7, failed.getInt("exit_code"))
            assertEquals("failed", failed.getString("stderr"))
            val merged = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("command", "printf out; printf err >&2").put("promote_on_timeout", true).put("merge_stderr", true)
                .put("timeout_ms", 3000).put("cwd", temporaryFolder.root.path)))
            assertEquals("out\n[stderr]\nerr", merged.getString("stdout"))
            assertEquals("", merged.getString("stderr"))
        }
    }

    @Test fun promotionContinuesTheSameProcessAfterForegroundTimeoutAndControllerClose() {
        withBackgroundController { controller, jobs ->
            val launches = File(temporaryFolder.root, "launches")
            val result = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("command", "printf launch >> ${shellQuote(launches.path)}; printf ready; sleep 0.6; printf '完成😀'")
                .put("promote_on_timeout", true).put("timeout_ms", 80).put("cwd", temporaryFolder.root.path)))
            assertTrue(result.toString(), result.getBoolean("ok"))
            assertEquals("promoted", result.getString("status"))
            assertTrue(result.getBoolean("promoted"))
            assertFalse(result.getBoolean("timed_out"))
            assertTrue(result.getString("output_tail").contains("ready"))
            assertTrue(File(result.getString("output_path")).exists())
            val id = result.getString("job_id")
            controller.closeAll()
            val waited = JSONObject(jobs.waitFor(id, 5000) { false })
            assertEquals("completed", waited.getString("status"))
            val output = JSONObject(jobs.read(id))
            assertTrue(output.getString("output").contains("ready完成😀"))
            assertEquals("launch", launches.readText())
            assertEquals(result.getString("output_path"), output.getString("output_path"))
        }
    }

    @Test fun cancellingOptedInForegroundWaitStopsItsTaskAndReturnsCancelled() {
        withBackgroundController { controller, jobs ->
            val marker = File(temporaryFolder.root, "running")
            val after = File(temporaryFolder.root, "must-not-finish")
            val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<String> { controller.executeTool("terminal", JSONObject()
                    .put("command", "touch ${shellQuote(marker.path)}; sleep 30; touch ${shellQuote(after.path)}")
                    .put("promote_on_timeout", true).put("timeout_ms", 10000).put("cwd", temporaryFolder.root.path)) }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (!marker.exists() && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(marker.exists())
                controller.closeAll()
                val result = JSONObject(future.get(3, java.util.concurrent.TimeUnit.SECONDS))
                assertFalse(result.toString(), result.getBoolean("ok"))
                assertEquals("cancelled", result.getString("status"))
                assertFalse(result.getBoolean("timed_out"))
                assertFalse(result.optBoolean("promoted"))
                assertEquals(0, JSONObject(jobs.list(true)).getJSONArray("tasks").length())
                assertFalse(after.exists())
            } finally { executor.shutdownNow() }
        }
    }

    @Test fun sessionExecIgnoresPromotionAndFallbackKeepsDefaultTimeoutBehavior() {
        withBackgroundController { controller, jobs ->
            val id = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("action", "open").put("cwd", temporaryFolder.root.path))).getString("session_id")
            val result = JSONObject(controller.executeTool("terminal", JSONObject().put("session_id", id)
                .put("promote_on_timeout", true).put("command", "printf session")))
            assertEquals("session", result.getString("stdout"))
            assertEquals(id, result.getString("session_id"))
            assertEquals(0, JSONObject(jobs.list(false)).getJSONArray("tasks").length())
            val defaultTimeout = JSONObject(controller.executeTool("terminal", JSONObject()
                .put("command", "sleep 30").put("timeout_ms", 1).put("cwd", temporaryFolder.root.path)))
            assertTrue(defaultTimeout.getBoolean("timed_out"))
            assertFalse(defaultTimeout.optBoolean("promoted"))
        }
        val fallback = RootShellTerminalController(NoopLogger, rootAvailable = { false }, executionContext = executionContext())
        try {
            val result = JSONObject(fallback.executeTool("terminal", JSONObject().put("command", "printf fallback")
                .put("promote_on_timeout", true).put("cwd", temporaryFolder.root.path)))
            assertEquals("fallback", result.getString("stdout"))
            assertEquals("exited", result.getString("status"))
        } finally { fallback.closeAll() }
    }

    private fun withBackgroundController(block: (RootShellTerminalController, BackgroundTerminalJobs) -> Unit) {
        val supervisor = DetachedTaskSupervisor(NoopLogger, File(temporaryFolder.root, "promote-jobs.json"),
            daemonDir = File(temporaryFolder.root, "daemon").path, rootAvailable = { false })
        val jobs = BackgroundTerminalJobs(supervisor, NoopLogger)
        val controller = RootShellTerminalController(NoopLogger, detachedSupervisor = supervisor, backgroundJobs = jobs,
            rootAvailable = { false }, executionContext = executionContext())
        try { block(controller, jobs) } finally { controller.closeAll(); jobs.close() }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private class RecordingLogger : AgentLogger {
        val messages = mutableListOf<String>()

        override fun debug(message: () -> String) {
            messages += message()
        }

        override fun info(message: String) {
            messages += message
        }

        override fun warn(message: String) {
            messages += message
        }

        override fun error(message: String, throwable: Throwable?) {
            messages += message
        }
    }
}
