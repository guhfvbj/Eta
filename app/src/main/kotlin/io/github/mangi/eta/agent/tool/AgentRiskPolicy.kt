package io.github.mangi.eta.agent.tool

import java.util.Locale
import org.json.JSONObject

/** 危险度分级：越高，确认的粒度越严、文案要求越明确。 */
internal enum class AgentRiskLevel { LOW, MEDIUM, HIGH, FATAL }

/**
 * 确认粒度：
 * - [NONE] 不打断；
 * - [ONCE] 每次都要确认（同一次授权只放行一次）；
 * - [SESSION] 本会话内同一调用（工具 + 参数完全相同）确认一次后不再重复询问。
 */
internal enum class AgentConfirmationScope { NONE, ONCE, SESSION }

/**
 * 一次调用的风险判定结果。纯数据 + 纯函数产出，便于单测。
 *
 * [reasons] 直接用于确认文案：必须让用户看懂「会发生什么」，而不是只看到工具名。
 */
internal data class AgentRiskAssessment(
    val level: AgentRiskLevel,
    val reasons: List<String> = emptyList(),
    /**
     * 审查档位对授权粒度的覆盖：严格审查档把中风险的「本会话允许一次」
     * 压成逐次确认（一次性）。null = 不覆盖，按 [level] 推导。
     */
    val scopeOverride: AgentConfirmationScope? = null,
) {
    val scope: AgentConfirmationScope
        get() = scopeOverride ?: when (level) {
            AgentRiskLevel.LOW -> AgentConfirmationScope.NONE
            AgentRiskLevel.MEDIUM -> AgentConfirmationScope.SESSION
            // 高危与致命操作不允许「本会话一直允许」：授权只对这一次生效。
            AgentRiskLevel.HIGH, AgentRiskLevel.FATAL -> AgentConfirmationScope.ONCE
        }

    val requiresConfirmation: Boolean get() = scope != AgentConfirmationScope.NONE
}

/**
 * 统一的危险操作风险判定层。
 *
 * 设计取舍：
 * - 只有这一张表与一个入口 [assess]，工具目录与执行侧的判断不会分叉；
 * - 判定结果是纯函数（不读磁盘、不看运行状态），唯一的外部输入是工具名与参数文本，
 *   因此可以用单测穷举危险/安全样本；
 * - 读类动作一律不打扰：[SAFE_ACTIONS] 里的动作名（list/get/current/reset/scan 等）先判安全，
 *   避免用户在正常浏览时被确认弹窗淹没；
 * - 不确定就升级风险（见 [AgentShellCommandRisk]），而不是放行。
 */
internal object AgentRiskPolicy {

    /** 会执行任意 shell 的工具。 */
    private val SHELL_TOOLS = setOf("terminal", "run_command", "terminal_job", "terminal_daemon")

    /** `terminal` 已知的动作名；不在这里的动作一律按高风险处理（新增动作不能默认放行）。 */
    private val TERMINAL_ACTIONS = setOf(
        "open", "exec", "open_and_exec", "read_async_result", "close",
        "daemon_start", "daemon_list", "daemon_logs", "daemon_stop", "check_root",
    )

    /** 这些动作不会执行命令，不打断用户。 */
    private val TERMINAL_READ_ACTIONS = setOf("open", "read_async_result", "close", "daemon_list", "daemon_logs")

    /**
     * 未在表里登记的新工具：名字里带这些动词时按「不确定的中风险」处理，先确认一次。
     * 这不为已知工具服务（它们都有明确映射），只防止新工具默认静默执行。
     */
    private val DESTRUCTIVE_NAME_HINTS = listOf(
        "delete", "remove", "uninstall", "wipe", "purge", "clear", "destroy", "erase", "format",
    )

    /** 未知工具上出现这些动作名时，同样先确认一次。 */
    private val DESTRUCTIVE_ACTIONS = setOf("delete", "remove", "purge", "wipe", "destroy")

    /** 语义上无害的动作名：与具体工具无关，出现在任何工具上都先判安全。 */
    private val SAFE_ACTIONS = setOf(
        "list", "current", "get", "scan", "reset", "reset_resolution", "reset_density", "reset_overscan",
    )

    /**
     * 判定一次工具调用。
     *
     * @param toolName 模型发起的工具名（大小写与空白会被规范化）
     * @param argumentsJson 模型给出的参数 JSON
     */
    fun assess(toolName: String, argumentsJson: String): AgentRiskAssessment {
        val name = toolName.trim().lowercase(Locale.ROOT)
        if (name.isEmpty()) return high("缺少工具名称")
        val args = argsOf(argumentsJson)
            ?: return AgentRiskAssessment(AgentRiskLevel.HIGH, listOf("参数不是合法的 JSON，无法确认实际会做什么"))
        if (name in SHELL_TOOLS) return assessShellTool(name, args)
        // 自进化配方的步骤由 [assessRecipe] 结合配方内容判定，单看工具名无法判断。
        if (name.startsWith(ToolRecipeConstants.TOOL_PREFIX)) return AgentRiskAssessment(AgentRiskLevel.LOW)
        return assessTool(name, args)
    }

    /**
     * 配方调用的判定：在工具名判定之上叠加配方里所有 Root shell 步骤的风险。
     * 配方是模型自己写的，只看 `recipe_x` 这个名字会把 `rm -rf` 包装成无害调用。
     */
    fun assessRecipe(
        toolName: String,
        argumentsJson: String,
        shellCommands: List<String>,
    ): AgentRiskAssessment {
        val own = assess(toolName, argumentsJson)
        if (shellCommands.isEmpty()) return own
        val worst = shellCommands
            .map { AgentShellCommandRisk.analyze(it) }
            .maxByOrNull { it.level } ?: return own
        return if (worst.level > own.level) {
            AgentRiskAssessment(worst.level, worst.reasons)
        } else {
            own
        }
    }

    private fun assessShellTool(name: String, args: JSONObject): AgentRiskAssessment {
        val action = args.optString("action", if (name == "terminal") "exec" else "").trim().lowercase(Locale.ROOT)
        when (name) {
            "terminal_job" -> when (action) {
                "list", "read", "wait" -> return low()
                "stop" -> return medium("终止后台任务")
                "start" -> Unit
                else -> return high("未识别的后台命令动作")
            }
            "terminal_daemon" -> when (action) {
                "list", "logs" -> return low()
                "stop" -> return medium("终止跨运行后台服务")
                "start" -> Unit
                else -> return high("未识别的后台服务动作")
            }
            "terminal" -> when {
                action == "check_root" -> return medium("请求并检测 Root 授权")
                action in TERMINAL_READ_ACTIONS -> return low()
                action == "daemon_stop" -> return medium("终止后台守护进程")
                action !in TERMINAL_ACTIONS -> return high("未识别的终端动作")
            }
        }
        val command = args.optString("command")
        if (command.isBlank()) return low()
        return AgentShellCommandRisk.analyze(command)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun assessTool(name: String, args: JSONObject): AgentRiskAssessment {
        val action = args.optString("action").trim().lowercase(Locale.ROOT)
        // Actions are interpreted only by their own tool; action="list" cannot downgrade a write tool.
        if (name in READ_TOOLS || name in io.github.mangi.eta.agent.context.PersonalSearchTools.names) return low()
        if (name == "subagent_profile" && action in setOf("list","get")) return low()
        if (name == "get_logcat") return medium("读取需要 Root 的系统日志，可能包含其他应用隐私")
        if (name in setOf("write_file", "edit_file", "skills_install_from_github", "skills_manage", "eta_author_tool"))
            return high("修改文件或持久化工具、引入第三方能力")
        if (name in setOf("type_text", "input_text", "replace_text", "clear_text", "paste_text", "set_clipboard",
                "create_calendar_event", "update_calendar_event", "create_note", "update_note", "character_memory_write",
                "tasks_create", "tasks_update", "tasks_run", "tasks_cancel", "tasks_delete", "set_alarm", "set_timer",
                "set_volume", "set_flashlight", "set_brightness", "set_screen_timeout", "set_sound_mode", "set_do_not_disturb",
                "subagent", "subagent_profile")) return medium("修改设备、个人数据或运行任务")
        if (name == "app_state_control") return if (action == "freeze") high("冻结应用可能中断系统功能") else medium("变更应用运行状态")
        io.github.mangi.eta.agent.model.AgentPhoneToolCatalog.entries.firstOrNull { it.name == name }?.let {
            return if (it.write) medium("修改${it.title}") else low()
        }
        if (name == "virtual_screen") return when(action) {
            "list", "get", "status" -> low()
            else -> medium("更改虚拟屏或应用路由")
        }
        if (name.startsWith("mcp_")) return medium("调用外部 MCP 工具")
        return when (name) {
            "power_control" -> when (action) {
                "reboot" -> fatal("重启设备：当前对话、正在运行的任务与未保存的现场都会中断")
                else -> medium("修改系统电源策略（省电模式 / 常亮 / Doze 白名单）")
            }

            "screen_action" -> when (action) {
                "reboot" -> fatal("重启设备：当前对话与正在运行的任务都会中断")
                "power_menu" -> medium("打开电源菜单，后续可能被误触重启或关机")
                else -> low()
            }

            "manage_app" -> when (action) {
                "uninstall" -> fatal("卸载应用：应用数据会一并删除，且无法撤销")
                "clear_data" -> fatal("清除应用数据：账户、聊天记录与本地文件会一并删除，且无法撤销")
                "install" -> high("安装 APK：会写入新应用，可能申请敏感权限")
                "grant_permission", "revoke_permission" ->
                    high("变更运行时权限：可能让应用获得或失去相机、位置、通讯录等能力")
                else -> medium("管理已安装应用")
            }

            "package_control" -> when (action) {
                "disable" -> fatal("停用（含系统）应用：依赖它的功能会立即失效，可能影响系统界面")
                "uninstall_updates" -> fatal("卸载系统应用更新：回退到出厂版本，应用数据可能被重置")
                "enable", "reinstall" -> medium("变更应用包状态")
                else -> low()
            }

            "user_control" -> when (action) {
                "remove" -> fatal("删除系统用户：该用户的全部数据会被清空且无法恢复")
                "switch", "create" -> medium("切换或创建系统用户，当前会话可能被切到后台")
                else -> low()
            }

            "manage_file" -> when (action) {
                "delete" -> fatal("删除设备文件或目录树：删除后无法从应用内恢复")
                "move" -> high("移动文件或目录：原位置的内容会被移走")
                "rename" -> medium("重命名文件或目录")
                else -> low()
            }

            "appops_control" -> if (action == "set") {
                high("修改应用的底层权限（AppOps）：可能导致应用偷偷获得或失去敏感能力")
            } else {
                low()
            }

            "system_property" -> if (action == "set") {
                high("写入系统属性：可能影响系统行为，需要 Root 且不一定能撤销")
            } else {
                low()
            }

            "display_control" -> if (action.startsWith("set_")) {
                medium("修改屏幕分辨率 / DPI / 边距：界面可能暂时错位，可用 reset_* 恢复")
            } else {
                low()
            }

            "ime_control" -> if (action == "set") {
                medium("切换默认输入法：键盘布局会立即改变")
            } else {
                low()
            }

            "connect_wifi" -> medium("连接 Wi-Fi：会写入网络凭据与系统网络配置")

            "set_timezone" -> medium("修改系统时区：所有应用的时间显示都会变化")

            "set_device_state" -> medium("切换 Wi-Fi / 蓝牙 / 飞行模式 / 移动数据等连通性开关")

            "set_setting" -> medium("写入系统设置项：会改变全局显示、亮度、输入等行为")

            "set_hotspot" -> medium("开启或关闭个人热点：可能影响当前网络连接")

            "skills_install_from_github" -> high("安装来自 GitHub 的 Skill：会引入并运行第三方说明与脚本")

            "skills_install_local" -> medium("安装本地目录的 Skill：会写入并启用第三方说明与脚本")

            "eta_manage_history" -> when (action) {
                "delete_conversation_forever" -> fatal("永久删除会话：内容不会进入回收站，无法恢复")
                "empty_trash" -> fatal("清空回收站：回收站里的全部会话会被永久删除")
                else -> low()
            }

            "memory_write" -> when (args.optString("mode").trim().lowercase(Locale.ROOT)) {
                "clear" -> fatal("清空全部助手记忆：MEMORY.md 的内容会被整体删除且无法恢复")
                else -> medium("写入持久助手记忆")
            }

            "manage_agent_workspace" -> when (action) {
                "merge" -> high("把子代理工作区的改动合并进当前工作树：会覆盖同名文件")
                "discard" -> high("丢弃子代理工作区的全部改动：未合并的内容会丢失")
                else -> low()
            }

            "eta_manage_models", "eta_configure_mcp" -> medium("修改供应商 / MCP 服务器配置，可能包含密钥与端点")

            "delegate_task" -> medium("创建子代理任务：子代理会使用本会话的工具能力")

            "delete_note", "delete_alarm", "delete_calendar_event" ->
                medium("删除用户的个人数据条目（便签 / 闹钟 / 日程）")

            // 只读，但内容属于凭据类：不按「只读一律放行」处理，本会话确认一次。
            "read_sms_code" -> medium("读取短信验证码：可能包含登录或支付的一次性凭据")
            "wifi_credentials" -> medium("读取已保存的 Wi-Fi 密码")

            else -> {
                // 未知工具默认按名称/动作里的不可逆动词兜底；完全看不出破坏性的新工具仍需登记到本表。
                val riskyName = DESTRUCTIVE_NAME_HINTS.firstOrNull { name.contains(it) }
                if (riskyName != null) {
                    medium("工具名包含「$riskyName」，无法确认具体影响，先确认一次")
                } else if (action in DESTRUCTIVE_ACTIONS) {
                    medium("动作「$action」可能不可逆，且该工具未登记风险等级，先确认一次")
                } else {
                    medium("未登记工具的具体效果，执行前需要确认")
                }
            }
        }
    }

    private val READ_TOOLS = setOf(
        "get_current_context", "search_apps", "observe_screen", "get_clipboard", "wait", "wait_for_text", "wait_for_package",
        "conversation_history", "read_conversation_history", "memory_get", "character_memory_get", "eta_list_recipes",
        "inspect_app", "device_status", "network_info", "get_setting", "get_current_location", "get_device_environment",
        "recent_notifications", "search_notification_history", "recent_app_activity", "app_usage_summary", "top_memory_apps", "top_storage_apps",
        "list_alarms", "list_active_timers", "get_health_summary", "read_personal_item", "summarize_bills", "read_note",
        "search_calendar_todos", "search_calendar_events", "search_personal_orders", "search_clipboard_history",
        "search_media", "search_audio", "search_recordings", "search_files", "search_contacts", "search_call_history", "search_messages",
        "search_downloads", "search_notes", "search_coloros_recordings", "search_recording_summaries", "search_system_memories",
        "search_saved_places", "search_qq_chat_images", "search_wechat_chat_images",
        "read_file", "list_directory", "read_image", "stat_file", "glob_files", "grep_files",
        "skills_list", "skills_read", "skills_read_resource", "skills_list_curated", "skills_inspect_github",
        "tasks_triggers", "tasks_list", "tasks_history", "web_search", "fetch_url",
        "tools_search", "tools_use_group"
    )

    private fun argsOf(raw: String): JSONObject? =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrNull()

    private fun low() = AgentRiskAssessment(AgentRiskLevel.LOW)

    private fun medium(reason: String) = AgentRiskAssessment(AgentRiskLevel.MEDIUM, listOf(reason))

    private fun high(reason: String) = AgentRiskAssessment(AgentRiskLevel.HIGH, listOf(reason))

    private fun fatal(reason: String) = AgentRiskAssessment(AgentRiskLevel.FATAL, listOf(reason))
}
