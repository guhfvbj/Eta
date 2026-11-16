package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.terminal.DetachedTask

/** 只给模型的稳定完成通报；日志按需增量读取，不把大量输出塞进新回合。 */
internal object AgentJobNotice {
    private const val GUIDANCE = "结果已保存在任务记录中；需要细节时用 `terminal_job` 的 `action=\"read\"` 配合 `from_offset` 读取增量输出，不要轮询。"

    private fun clip(value: String, limit: Int): String =
        if (value.length <= limit) value else value.take(limit - 1) + "…"

    fun build(task: DetachedTask): String {
        val status = when {
            task.endReason == "stopped" -> "被停止"
            task.endReason != null || (task.exitCode != null && task.exitCode != 0) -> "已终止"
            else -> "已完成"
        }
        val body = buildString {
            append("任务 ${task.id.take(128)} 的后台命令$status\n")
            append("退出码：${task.exitCode ?: "未知"}\n")
            append("结束原因：${task.endReason?.take(128) ?: "正常退出"}\n")
            append("命令：${task.command.replace(Regex("\\s+"), " ").trim().take(200)}\n")
            append("cwd：${clip(task.cwd, 700)}\n")
            append("delivery：${task.delivery.wireName}")
        }
        // 以 UTF-16 长度限额，同时始终保留结尾的工具使用引导。
        val remaining = 1500 - GUIDANCE.length - 2
        val bounded = if (body.length <= remaining) body else body.take(remaining - 1) + "…"
        return "$bounded\n\n$GUIDANCE"
    }
}
