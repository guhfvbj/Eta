package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    val names = setOf("terminal", "terminal_job", "terminal_daemon")

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "terminal",
                description = "执行 Shell 命令，默认环境、身份与目录见运行上下文；已知文件优先用文件工具。" +
                    "保留 Shell 状态用 open → session_id exec → close，会话不随 environment 参数切换；exit=127 时查看 environment_hint。" +
                    "勿用 & 挂后台；耗时命令用 terminal_job，跨运行服务用 terminal_daemon。",
                parameters = parameters(
                    commandProperties()
                        .put("action", action("exec", "open", "close", "check_root")
                            .put("description", "默认 exec 执行命令；open 创建持久 Shell；close 关闭会话；check_root 用固定的 su -c 'id -u' 检测并更新 Root 授权，不需要 command，可能显示系统授权请求。"))
                        .put("session_id", text("open 返回的会话 ID。exec 沿用会话的目录、环境和身份；切换目录请在会话中执行 cd。")),
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "terminal_job",
                description = "后台命令使用独立 Shell，不继承 terminal 会话，回复后继续执行。start 返回 job_id；完成或失败时通知，通知可用性见 completion_notifications_enabled。" +
                    "list 查状态，read 读日志，依赖结果时 wait，stop 终止；状态含 elapsed_ms 和精简 output_tail。" +
                    "完整日志在 output_path，截断时用 read_file 分页读取；启动后可继续工作或回复，无需轮询。",
                parameters = parameters(
                    executionProperties()
                        .put("action", action("start", "list", "read", "wait", "stop"))
                        .put("command", text("start 要执行的完整命令。"))
                        .put("description", text("可选的简短任务说明，用于列表和完成通知。"))
                        .put("timeout_ms", integer("start 的命令运行上限，默认 600000 毫秒；0 表示不设超时。"))
                        .put("job_id", text("start 或 list 返回的任务 ID；read、wait、stop 使用。"))
                        .put("active_only", bool("list 是否只返回运行中的任务，默认 true。"))
                        .put("max_bytes", integer("read 返回的日志尾部字节数，默认 16000，最多 65536；完整日志保存在 output_path。"))
                        .put("wait_ms", integer("wait 最长等待毫秒数，默认 30000，最多 60000；等待超时不会停止命令。")),
                    "action",
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "terminal_daemon",
                description = "管理需要跨 Agent 运行保留的后台服务，例如 Web 服务。start 启动并返回 task_id，" +
                    "list 查看任务，logs 读取日志，stop 停止任务。服务不会随当前运行结束自动回收，" +
                    "但仍可能因进程退出、系统回收、权限变化或设备重启而终止。",
                parameters = parameters(
                    executionProperties()
                        .put("action", action("start", "list", "logs", "stop"))
                        .put("command", text("start 要执行的命令，使用实际环境支持的 Shell 语法。"))
                        .put("task_id", text("start 或 list 返回的任务 ID；logs、stop 使用。")),
                    "action",
                ),
            ),
        )
    }

    private fun executionProperties(): JSONObject = JSONObject()
        .put("environment", text("默认 android，用于系统/应用/设备；Git、Python/Node、jq/rg/curl、包管理、构建和 APK 分析显式用 linux。共享目录映射与路径见运行上下文。")
            .put("enum", JSONArray(listOf("android", "linux"))))
        .put("identity", text("可选的宿主执行身份；Android 已确认 Root 授权时默认 root，否则 user。已授权时无需先尝试 user 或重复检测；显式 user 使用 Eta App UID。Linux 内模拟 root 不提供 Android 特权。")
            .put("enum", JSONArray(listOf("user", "root"))))
        .put("cwd", text("可选工作目录；省略时使用当前环境默认工作区，相对路径也以默认工作区为基准。"))

    private fun commandProperties(): JSONObject = executionProperties()
        .put("command", text("要执行的命令，使用实际环境支持的 Shell 语法。"))
        .put("timeout_ms", integer("命令超时毫秒，默认 30000，最多 180000。安装、构建或长脚本请用 terminal_job，或显式提高此上限。超时会终止进程树；会话命令超时也会关闭会话。检查 timed_out/code；exit 143 可能是 SIGTERM，单凭退出码不代表工具超时。"))
        .put("merge_stderr", bool("是否把 stderr 合并到 stdout，默认 false。"))

    private fun parameters(properties: JSONObject, vararg required: String): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required.toList()))
        .put("additionalProperties", false)

    private fun action(vararg values: String): JSONObject = text("要执行的动作。")
        .put("enum", JSONArray(values.toList()))

    private fun text(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
}
