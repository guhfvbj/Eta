package io.github.mangi.eta.agent.model

/** Taxonomy adapted from Sam-Fic/EtaExpressive to oo0520's existing tool names. */
internal data class AgentToolGroup(val id: String, val summary: String, val toolNames: Set<String> = emptySet())
internal object AgentToolGroups {
    val BASIC_GROUP = AgentToolGroup("basic", "当前上下文、应用检索、会话读取、技能读取与子代理")
    val SCREEN_GROUP = AgentToolGroup("screen", "屏幕观察、GUI 操作和多会话虚拟屏")
    val WEB_GROUP = AgentToolGroup("web", "网页搜索、抓取和浏览器")
    val TERMINAL_GROUP = AgentToolGroup("terminal", "终端与文件读写")
    val DEVICE_GROUP = AgentToolGroup("device", "设备控制与系统设置")
    val CALENDAR_GROUP = AgentToolGroup("calendar", "日程读取与修改")
    val PERSONAL_GROUP = AgentToolGroup("personal", "ColorOS、Vivo 等个人数据检索")
    val DEVICE_STATE_GROUP = AgentToolGroup("device_state", "设备状态、通知与位置")
    val EXTENSIONS_GROUP = AgentToolGroup("extensions", "创建和管理工具配方、技能、记忆与自动任务")
    val groups = listOf(BASIC_GROUP, SCREEN_GROUP, WEB_GROUP, TERMINAL_GROUP, DEVICE_GROUP,
        CALENDAR_GROUP, PERSONAL_GROUP, DEVICE_STATE_GROUP, EXTENSIONS_GROUP)
    fun classify(name: String): String = when {
        name.startsWith("recipe_") || name.startsWith("eta_") || name.startsWith("tasks_") ||
            name == "memory_write" || name.startsWith("skills_install") || name == "skills_manage" -> "extensions"
        name.contains("calendar") -> "calendar"
        name in setOf("browser_use", "fetch_url", "web_search") -> "web"
        name in setOf("terminal", "terminal_job", "terminal_daemon", "run_command", "read_file", "write_file", "edit_file", "list_directory", "glob_files", "grep_files", "stat_file", "read_image") -> "terminal"
        name in setOf("get_current_context", "search_apps", "launch_app", "open_uri", "memory_get", "read_conversation_history") ||
            name.startsWith("skills_read") || name.startsWith("skills_list") || name.contains("task_result") || name.startsWith("delegate_") -> "basic"
        name.contains("screen") || name in setOf("tap", "tap_area", "tap_smart", "tap_element", "long_press", "long_press_element", "swipe", "scroll", "scroll_element", "type_text", "input_text", "replace_text", "clear_text", "press_key", "get_clipboard", "set_clipboard", "paste_text", "wait", "wait_for_text", "wait_for_package", "open_system_panel", "lingscreen_open_app") -> "screen"
        name.startsWith("search_") || name.contains("sms") || name.contains("contacts") || name.contains("memories") -> "personal"
        name.startsWith("get_") || name.startsWith("recent_") || name in setOf("device_status", "network_info", "top_memory_apps", "top_storage_apps", "app_usage_summary") -> "device_state"
        else -> "device"
    }
}
