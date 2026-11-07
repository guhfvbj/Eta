package io.github.mangi.eta.agent.model

import android.content.Context
import io.github.mangi.eta.agent.decision.*
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.tool.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Owns only feature overlays; local/MCP execution still passes through oo0520's existing router. */
internal class AgentFeatureSession(
    private val context: Context,
    private val conversation: String,
    private val prompt: String,
    private val history: List<AgentModelClient.ConversationMessage>,
    private val config: AgentModelClient.ModelConfig,
    private val controller: AgentRunController,
    private val rootAvailable: () -> Boolean,
    private val mcpGroups: Map<String, String> = emptyMap(),
    private val writable: Boolean = true,
) {
    private val store = RecipeRuntime.store(context)
    private val author = AgentSelfAuthorTools(store, rootAvailable)
    private val recipeExecutor = AgentToolRecipeExecutor(context,
        BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable), store, rootAvailable, ::checkRecipeStep)
    private val settings = AgentFeaturePreferences.load(context)
    private val sessionPrefs = context.getSharedPreferences("eta_tool_sessions", Context.MODE_PRIVATE)
    private val mode = sessionPrefs.getString("mode_$conversation", null) ?: settings.optString("loading", "tool").also {
        sessionPrefs.edit().putString("mode_$conversation", it).apply()
    }
    private val opened = sessionPrefs.getStringSet("opened_$conversation", emptySet()).orEmpty().toMutableSet()
    private val openedGroups = mutableSetOf<String>()
    private val sessionGrants = mutableSetOf<String>()
    private var catalog = emptyMap<String, JSONObject>()
    private var published = emptySet<String>()
    private var decisionStarted = false
    private var allowedGroups: Set<String>? = null
    private fun group(name: String) = mcpGroups[name] ?: AgentToolGroups.classify(name)
    private fun description(id: String, names: List<String>): String {
        val summary = AgentToolGroups.groups.firstOrNull { it.id == id }?.summary ?: "MCP 工具服务器"
        val indexed = if (id.startsWith("mcp:")) names else names.filter { it.startsWith("recipe_") }
        return summary + indexed.take(16).joinToString(prefix = if (indexed.isEmpty()) "" else ": ", separator = "; ") { name ->
            "$name: ${catalog[name]?.optJSONObject("function")?.optString("description").orEmpty().take(160)}"
        }
    }
    private fun permitted(name: String): Boolean {
        val prefs = AgentFeaturePreferences.load(context)
        val disabled = prefs.optString("disabled_tools").split(',', '\n').map { it.trim() }
        return name !in disabled && prefs.optBoolean("group_${group(name)}", true) &&
            (!name.startsWith("recipe_") && !name.startsWith("eta_") || writable && prefs.optBoolean("recipes_enabled", true))
    }
    fun extraSchemas(): JSONArray = JSONArray().also { tools ->
        if (writable && AgentFeaturePreferences.load(context).optBoolean("recipes_enabled", true)) {
            store.invalidate()
            store.schemas(rootAvailable()).forEach(tools::put)
            tools.put(schema("eta_author_tool", "Create/update/delete/enable/disable a persistent declarative tool recipe. Step fields: http_request(url,method,headers,body); root_shell(command,timeout_ms); settings_read/write(namespace,key,value); file_read(path); file_write(path,content); content_query(uri,projection,selection,selection_args); compose(recipe,arguments); literal(text). Supported step op: http_request,root_shell,settings_read,settings_write,file_read,file_write,content_query,compose,literal. Root steps require Root; changes require authorization. Next round activation.", JSONObject()
                .put("action", property("string").put("enum", JSONArray(listOf("create", "update", "delete", "enable", "disable"))))
                .put("name", property("string")).put("description", property("string"))
                .put("parameters", property("object")).put("steps", property("array").put("items", property("object"))), listOf("action", "name")))
            tools.put(schema("eta_list_recipes", "List persistent tool recipes and their current state", JSONObject().put("name", property("string"))))
        }
    }
    fun project(full: JSONArray): JSONArray {
        catalog = (0 until full.length()).map { full.getJSONObject(it) }
            .associateBy { it.getJSONObject("function").getString("name") }.filterKeys(::permitted)
        if (!decisionStarted) {
            decisionStarted = true
            val groups = catalog.keys.groupBy(::group).map { (id, names) ->
                AgentToolGroup(id, description(id, names), names.toSet())
            }
            val historyText = history.takeLast(8).joinToString("\n") { "${it.role}: ${it.content}" }
            if (settings.optBoolean("decision_enabled")) {
                if (settings.optBoolean("decision_shadow", true)) {
                    SHADOW.execute { AgentFeatureDecision.evaluate(settings, groups, prompt, historyText, controller)?.let {
                        AndroidAgentLogger.info("decision shadow selected=${it.selected} confidence=${it.confidence}")
                    } }
                } else {
                    val decision = AgentFeatureDecision.evaluate(settings, groups, prompt, historyText, controller)
                    if (settings.optString("decision_mode", "advise") == "control") {
                        allowedGroups = decision?.selected ?: if (settings.optString("decision_failure", "open") == "closed") emptySet() else null
                    }
                    openedGroups += decision?.selected.orEmpty()
                }
            }
            // Restore only tool names that remain permitted. Compaction cannot create new permissions.
            for (message in history) runCatching {
                val calls = JSONArray(message.toolCallsJson.ifBlank { "[]" })
                for (i in 0 until calls.length()) opened += calls.getJSONObject(i).optJSONObject("function")?.optString("name").orEmpty()
            }
        }
        val eligible = catalog.filterKeys { group(it) == "basic" || allowedGroups?.contains(group(it)) != false }
        val result = JSONArray()
        eligible.forEach { (name, tool) ->
            if (mode == "off" || group(name) == "basic" || name in opened || group(name) in openedGroups ||
                name.startsWith("mcp_") && !settings.optBoolean("mcp_deferred", true)) result.put(tool)
        }
        val groupIndex = eligible.keys.groupBy(::group).entries.joinToString("; ") { (id, names) ->
            "$id (${names.size}): ${description(id, names)}"
        }
        result.put(schema("tools_use_group", "Load a permitted group or exact tools for the NEXT round. Loading grants no device permissions. Available groups: $groupIndex",
            JSONObject().put("group", property("string")).put("tools", property("array").put("items", property("string")))))
        result.put(schema("tools_search", "Find permitted tools by keywords or select:name1,name2; load matches for NEXT round. Does not execute target tools.",
            JSONObject().put("query", property("string")).put("max_results", property("integer")), listOf("query")))
        published = (0 until result.length()).map { result.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        return result
    }
    fun execute(call: AgentModelClient.ToolCall, delegate: AgentModelClient.ToolExecutor, requireLoaded: Boolean = true): AgentModelClient.ToolResult {
        controller.throwIfCancelled()
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrElse { return error("INVALID_ARGUMENT", "参数不是 JSON 对象") }
        if (call.name in setOf("tools_use_group", "tools_search")) {
            val eligible = catalog.filterKeys { permitted(it) && (group(it) == "basic" || allowedGroups?.contains(group(it)) != false) }
            val selected = if (call.name == "tools_search") {
                val candidates = eligible.map { (name, schema) -> AgentToolSearch.Candidate(name,
                    schema.optJSONObject("function")?.optString("description"), null, name.startsWith("mcp_"), group(name)) }
                AgentToolSearch.search(args.optString("query"), candidates, args.optInt("max_results", 5).coerceIn(1, 20)).map { it.name }
            } else if (args.has("tools")) {
                val array = args.getJSONArray("tools"); (0 until array.length()).map { array.getString(it) }
            } else eligible.keys.filter { group(it) == args.optString("group") }
            if (call.name == "tools_use_group" && selected.isEmpty()) return error("GROUP_UNAVAILABLE", "该组不存在、已关闭或本轮被判断模型限制")
            if (selected.any { it !in eligible }) return error("TOOL_NOT_ALLOWED", "目标工具未获用户或判断模型允许")
            if (mode == "group" && call.name == "tools_search") openedGroups += selected.map(::group) else opened += selected
            sessionPrefs.edit().putStringSet("opened_$conversation", opened.toList().takeLast(512).toSet() +
                eligible.keys.filter { group(it) in openedGroups }).apply()
            return AgentModelClient.ToolResult(JSONObject().put("ok", true).put("available_next_round", JSONArray(selected)).toString())
        }
        if (requireLoaded && call.name !in published || !permitted(call.name) ||
            group(call.name) != "basic" && allowedGroups?.contains(group(call.name)) == false) return error("TOOL_NOT_LOADED", "工具未加载、已停用或权限已撤销；先搜索或加载")
        val recipe = if (call.name.startsWith("recipe_")) store.find(call.name.removePrefix("recipe_")) else null
        if (call.name.startsWith("recipe_") && (recipe == null || !recipe.enabled)) return error("RECIPE_DISABLED", "配方未启用")
        if (recipe != null && !rootAvailable() && recipe.name in store.rootDependentRecipes(store.all())) return error("ROOT_REQUIRED", "配方或组合依赖需要 Root")
        val risk = when {
            recipe != null -> AgentRiskAssessment(if (recipe.name in store.rootDependentRecipes(store.all())) AgentRiskLevel.FATAL else AgentRiskLevel.HIGH, listOf("执行模型自建配方（含组合依赖），可能读取隐私或修改文件与设置"))
            call.name == "eta_author_tool" -> AgentRiskAssessment(AgentRiskLevel.HIGH, listOf("修改持久化工具配方与能力边界"))
            call.name.startsWith("mcp_") -> AgentRiskAssessment(AgentRiskLevel.MEDIUM, listOf("调用外部 MCP 工具"))
            call.name.startsWith("set_") || call.name.contains("send_") || call.name.contains("delete_") ||
                call.name in setOf("terminal_daemon", "terminal_job", "app_state_control", "write_file", "edit_file") -> AgentRiskAssessment(AgentRiskLevel.HIGH, listOf("修改设备状态或文件"))
            else -> AgentRiskPolicy.assess(call.name, call.argumentsJson)
        }
        if (!authorize(call, risk)) return error("TOOL_AUTHORIZATION_DENIED", "工具审查或用户授权未通过，本次未执行")
        if (recipe != null) return try { AgentModelClient.ToolResult(recipeExecutor.execute(recipe, args), sensitive = true) }
            catch (failure: Exception) { controller.throwIfCancelled(); error("RECIPE_EXECUTION_DENIED", failure.message.orEmpty()) }
        author.execute(call.name, args)?.let { return AgentModelClient.ToolResult(it, sensitive = true) }
        return delegate.execute(call)
    }
    private fun checkRecipeStep(step: ToolRecipeStep) {
        controller.throwIfCancelled()
        val preferences = AgentFeaturePreferences.load(context)
        check(preferences.optBoolean("recipes_enabled", true)) { "配方权限已撤销" }
        val permission = when (step) {
            is ToolRecipeStep.RootShell -> "recipe_root"
            is ToolRecipeStep.HttpRequest -> "recipe_http"
            is ToolRecipeStep.SettingsWrite -> "recipe_settings"
            is ToolRecipeStep.ContentQuery -> "recipe_content"
            is ToolRecipeStep.FileRead, is ToolRecipeStep.FileWrite -> "recipe_files"
            else -> null
        }
        if (permission != null) check(preferences.optBoolean(permission, permission in setOf("recipe_http", "recipe_files"))) { "配方步骤未获权限：$permission" }
        if (step is ToolRecipeStep.RootShell) check(config.terminalTools && rootAvailable()) { "Root 或终端权限不可用" }
        if (step is ToolRecipeStep.SettingsWrite) check(config.deviceSensitiveActionTools) { "设备写入权限已关闭" }
        if (step is ToolRecipeStep.ContentQuery) check(config.deviceSensitiveReadTools) { "设备敏感读取权限已关闭" }
    }
    fun allowsHostedSearch(): Boolean = AgentFeaturePreferences.load(context).optBoolean("group_web", true) &&
        (allowedGroups == null || "web" in allowedGroups.orEmpty())
    fun configForRound(): AgentModelClient.ModelConfig = config.copy(
        hostedWebSearchEnabled = config.hostedWebSearchEnabled && allowsHostedSearch(),
    )
    private fun isReadOnly(call: AgentModelClient.ToolCall): Boolean {
        if (call.name.startsWith("mcp_") || call.name.startsWith("recipe_")) return false
        if (call.name in setOf("eta_list_recipes", "observe_screen", "memory_get", "read_image", "device_status", "network_info")) return true
        return listOf("read_", "get_", "search_", "list_", "stat_", "glob_", "grep_", "recent_", "skills_read", "skills_list").any(call.name::startsWith)
    }
    private fun authorize(call: AgentModelClient.ToolCall, risk: AgentRiskAssessment): Boolean {
        val current = AgentFeaturePreferences.load(context)
        val mode = current.optString("review_mode", "standard")
        if (mode == "readonly" && (risk.level != AgentRiskLevel.LOW ||
                !isReadOnly(call))) return false
        if (mode == "auto" && risk.level < AgentRiskLevel.FATAL) return true
        if (mode == "smart" && risk.level != AgentRiskLevel.FATAL) {
            val verdict = AgentFeatureReview.review(current, call, prompt, config, controller, history)
            if (verdict == false) return false
            if (verdict == true && risk.level < AgentRiskLevel.HIGH) return true
        }
        if (risk.level == AgentRiskLevel.LOW) return true
        val grantKey = call.name + "\u0000" + call.argumentsJson
        if (risk.level == AgentRiskLevel.MEDIUM && mode != "strict" && grantKey in sessionGrants) return true
        val allowed = AgentToolAuthorization.confirm(conversation, call.name, call.argumentsJson, risk, controller, repeatAllowed = risk.level == AgentRiskLevel.MEDIUM && mode != "strict")
        if (allowed && risk.level == AgentRiskLevel.MEDIUM && mode != "strict") sessionGrants += grantKey
        return allowed
    }
    private fun error(code: String, message: String) = AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", code).put("message", message).toString())
    companion object {
        private val SHADOW = java.util.concurrent.ThreadPoolExecutor(1, 2, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue(8), java.util.concurrent.ThreadFactory { r -> Thread(r, "eta-decision-shadow").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
        fun property(type: String) = JSONObject().put("type", type)
        fun schema(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()): JSONObject =
            JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
                .put("parameters", property("object").put("properties", properties).put("required", JSONArray(required))))
    }
}
