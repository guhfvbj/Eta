package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.context.PersonalSearchTools

internal data class AgentToolGroup(val id: String, val summary: String, val toolNames: Set<String> = emptySet())
/** Exact membership for oo0520's real catalog. Unknown names are never silently put in device. */
internal object AgentToolGroups {
    val BASIC_GROUP = AgentToolGroup("basic", "当前上下文、应用检索、会话读取、技能读取与子代理", setOf(
        "get_current_context", "search_apps", "launch_app", "open_uri", "memory_get", "character_memory_get",
        "conversation_history", "subagent", "skills_list", "skills_read", "skills_read_resource", "skills_list_curated"))
    val SCREEN_GROUP = AgentToolGroup("screen", "屏幕观察、GUI 操作和多会话虚拟屏", setOf(
        "observe_screen", "tap", "tap_area", "tap_smart", "tap_element", "long_press", "long_press_element", "swipe", "scroll", "scroll_element",
        "type_text", "input_text", "replace_text", "clear_text", "press_key", "get_clipboard", "set_clipboard", "paste_text",
        "wait", "wait_for_text", "wait_for_package", "open_system_panel", "lingscreen_open_app", "virtual_screen"))
    val WEB_GROUP = AgentToolGroup("web", "网页搜索、抓取和浏览器", setOf("browser_use", "fetch_url", "web_search"))
    val TERMINAL_GROUP = AgentToolGroup("terminal", "终端、后台命令与文件读写", setOf(
        "terminal", "terminal_job", "terminal_daemon", "run_command", "read_file", "write_file", "edit_file", "list_directory", "glob_files", "grep_files", "stat_file", "read_image"))
    val DEVICE_GROUP = AgentToolGroup("device", "设备控制与系统设置", setOf(
        "set_alarm", "set_timer", "inspect_app", "media_control", "set_volume", "set_setting", "set_device_state", "app_state_control",
        "set_flashlight", "set_brightness", "set_screen_timeout", "set_sound_mode", "set_do_not_disturb", "set_hotspot",
        "update_alarm_time", "set_alarm_enabled", "delete_alarm", "create_note", "delete_note"))
    val CALENDAR_GROUP = AgentToolGroup("calendar", "日程读取、创建、修改与删除", setOf(
        "list_calendars", "read_calendar_event", "search_calendar_events", "create_calendar_event", "create_calendar_events", "update_calendar_event", "delete_calendar_event"))
    val PERSONAL_GROUP = AgentToolGroup("personal", "个人内容：联系人、短信、便签、日历待办、账单和行程", PersonalSearchTools.names + setOf(
        "read_note", "search_personal_orders", "search_clipboard_history", "search_media", "search_audio", "search_recordings", "search_files", "search_contacts", "search_call_history", "search_messages",
        "search_downloads", "search_notes", "search_coloros_recordings", "search_recording_summaries", "search_system_memories", "search_saved_places", "search_qq_chat_images", "search_wechat_chat_images"))
    val DEVICE_STATE_GROUP = AgentToolGroup("device_state", "设备状态、通知、日志、位置、闹钟和计时器", setOf(
        "device_status", "network_info", "get_setting", "recent_notifications", "search_notification_history", "recent_app_activity", "get_current_location", "get_device_environment",
        "top_memory_apps", "top_storage_apps", "app_usage_summary", "wifi_credentials", "read_sms_code", "get_logcat", "list_alarms", "list_active_timers", "get_health_summary",
        "get_flashlight", "get_device_state", "get_display_state", "get_sound_state", "get_hotspot"))
    val EXTENSIONS_GROUP = AgentToolGroup("extensions", "创建管理工具配方、技能、记忆、自动任务与子代理模板", setOf(
        "eta_author_tool", "eta_list_recipes", "subagent_profile", "skills_inspect_github", "skills_install_from_github", "skills_manage", "memory_write", "character_memory_write",
        "tasks_triggers", "tasks_list", "tasks_create", "tasks_update", "tasks_delete", "tasks_run", "tasks_cancel", "tasks_history"))
    val groups = listOf(BASIC_GROUP, SCREEN_GROUP, WEB_GROUP, TERMINAL_GROUP, DEVICE_GROUP, CALENDAR_GROUP, PERSONAL_GROUP, DEVICE_STATE_GROUP, EXTENSIONS_GROUP)
    val deferredGroups = groups.filter { it.id != "basic" }
    val allIds = groups.map { it.id }.toSet()
    private val membership = buildMap { groups.forEach { group -> group.toolNames.forEach { name ->
        check(put(name, group.id) == null) { "工具重复分组：$name" }
    } } }
    fun classify(name: String): String = if(name.startsWith("recipe_")) "extensions" else membership[name] ?: "unclassified"
    fun deferredGroupsOf(names: Set<String>): Set<String> = names.map(::classify).filter { it != "basic" }.toSet()
}
