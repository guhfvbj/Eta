package io.github.mangi.eta.agent.terminal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure result tests also run on Windows JVMs without starting a POSIX shell. */
class TerminalCommandDiagnosticsTest {
    @Test fun androidCommandNotFoundExplainsLinuxAndPersistentSessionSelection() {
        for (ready in listOf(false, true)) {
            val result = TerminalCommandDiagnostics.apply(result(127, "ash: python3: not found"), ready, 30_000)
            assertEquals("COMMAND_NOT_FOUND", result.getString("code"))
            assertEquals(ready, result.getBoolean("linux_ready"))
            assertTrue(result.getString("environment_hint").contains("environment=linux"))
            if (ready) assertTrue(result.getString("environment_hint").contains("session_id"))
            assertEquals("ash: python3: not found", result.getString("stderr"))
            assertEquals(127, result.getInt("exit_code"))
        }
    }

    @Test fun linuxMissingCommandDoesNotSuggestSwitchingEnvironments() {
        val result = TerminalCommandDiagnostics.apply(result(127, "sh: node: not found").put("environment", "debian"), true, 30_000)
        assertEquals("COMMAND_NOT_FOUND", result.getString("code"))
        assertFalse(result.has("environment_hint"))
        assertFalse(result.has("linux_ready"))
    }

    @Test fun explicitExit127AndOrdinaryFailuresAreNotMistakenForMissingCommands() {
        for (exit in listOf(0, 1, 127)) {
            val result = TerminalCommandDiagnostics.apply(result(exit, "fixture"), true, 30_000)
            assertFalse(result.has("code"))
            assertEquals(exit == 0, result.getBoolean("ok"))
        }
    }

    @Test fun timeoutCancellationAndSignalLikeExitsRemainDistinguishable() {
        val timeout = TerminalCommandDiagnostics.apply(result(-2).put("timed_out", true).put("status", "timed_out"), true, 180_000)
        assertEquals("COMMAND_TIMEOUT", timeout.getString("code"))
        assertTrue(timeout.getString("message").contains("terminal_job"))
        assertEquals(180_000, timeout.getInt("timeout_ms"))
        val cancelled = TerminalCommandDiagnostics.apply(result(143).put("status", "cancelled"), true, 30_000)
        assertEquals("CANCELLED", cancelled.getString("code"))
        assertFalse(cancelled.has("possible_signal"))
        for ((exit, signal) in listOf(143 to "SIGTERM", 137 to "SIGKILL", 130 to "SIGINT")) {
            val result = TerminalCommandDiagnostics.apply(result(exit), true, 30_000)
            assertEquals("COMMAND_SIGNAL_EXIT", result.getString("code"))
            assertEquals(signal, result.getString("possible_signal"))
            assertFalse(result.getBoolean("timed_out"))
        }
    }

    @Test fun mappedCwdFailureHasGuidanceWithoutChangingItsPath() {
        val result = TerminalCommandDiagnostics.apply(result(2, "cd: /workspace/project: No such file or directory")
            .put("cwd", "/workspace/project"), true, 30_000)
        assertEquals("/workspace/project", result.getString("cwd"))
        assertTrue(result.getString("environment_hint").contains("environment=linux"))
    }

    private fun result(exit: Int, stderr: String = "") = JSONObject().put("ok", exit == 0)
        .put("exit_code", exit).put("timed_out", false).put("status", if (exit == 0) "exited" else "failed")
        .put("environment", "android").put("cwd", "relative").put("stdout", "").put("stderr", stderr)
}
