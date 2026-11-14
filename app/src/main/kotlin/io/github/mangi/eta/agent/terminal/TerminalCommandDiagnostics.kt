package io.github.mangi.eta.agent.terminal

import org.json.JSONObject

/** Add recovery guidance without retrying a command that may already have side effects. */
internal object TerminalCommandDiagnostics {
    fun apply(result: JSONObject, linuxReady: Boolean, timeoutMs: Int): JSONObject = result.apply {
        put("timeout_ms", timeoutMs)
        val exit = optInt("exit_code")
        val environment = optString("environment")
        when {
            optString("status") == "cancelled" -> {
                put("ok", false).put("code", "CANCELLED").put("message", "命令因当前运行或会话关闭而终止")
            }
            optBoolean("timed_out") -> {
                put("code", "COMMAND_TIMEOUT")
                    .put("message", "命令达到 ${timeoutMs}ms 上限，已回收进程；会话超时会关闭会话。耗时任务请用 terminal_job，或显式设置 timeout_ms（最多 180000）。重试前确认已有副作用。")
            }
            exit == 127 && (optString("stderr") + optString("stdout")).let {
                it.contains("not found", ignoreCase = true) || it.contains("not recognized", ignoreCase = true)
            } -> {
                put("code", "COMMAND_NOT_FOUND").put("message", "当前执行环境找不到命令，请检查该环境的 PATH 与已安装工具")
                if (environment == "android") {
                    put("linux_ready", linuxReady)
                    put("environment_hint", if (linuxReady) {
                        "Linux 已就绪；Python/Node/Git/rg 等开发工具请显式使用 environment=linux 与 Linux cwd（通常 /workspace）。若使用 session_id，请新建 Linux 会话或省略 session_id；已有会话不会切换环境。重试前确认命令已有副作用。"
                    } else {
                        "开发工具通常属于 Linux 环境；请先在设置中配置 Linux，再显式传 environment=linux，或检查 Android PATH 中是否安装了所需命令。"
                    })
                }
            }
            exit == 143 || exit == 137 || exit == 130 -> {
                val signal = when (exit) { 143 -> "SIGTERM"; 137 -> "SIGKILL"; else -> "SIGINT" }
                put("code", "COMMAND_SIGNAL_EXIT").put("possible_signal", signal)
                    .put("message", "exit=$exit 可能表示 $signal（或程序主动返回该码）；本工具未判定为超时。请检查外部停止、系统回收或命令自身的 timeout。")
            }
        }
        if (exit != 0 && !has("environment_hint")) {
            FileToolDispatcher.pathEnvironmentHint(optString("cwd"), if (environment == "android") "android" else "linux")
                ?.let { put("environment_hint", it) }
        }
    }
}
