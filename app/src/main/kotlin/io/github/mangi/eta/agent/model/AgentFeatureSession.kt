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
    private val runId: String = conversation,
    private val persistentSession: Boolean = true,
    private val onDecisionFallback: (String) -> Unit = {},
    private val mcpLabels: Map<String, String> = emptyMap(),
) {
    private val store = AgentToolRecipeStore.get(context)
    private val author = AgentSelfAuthorTools(store, rootAvailable)
    private val settings = AgentFeaturePreferences.load(context)
    private val sessionPrefs = context.getSharedPreferences("eta_tool_sessions", Context.MODE_PRIVATE)
    private val mode = sessionPrefs.getString("mode_$conversation", null) ?: settings.optString("loading", "search").also {
        if (persistentSession) sessionPrefs.edit().putString("mode_$conversation", it).apply()
    }
    private val opened = sessionPrefs.getStringSet("opened_$conversation", emptySet()).orEmpty().toMutableSet()
    private val openedGroups = sessionPrefs.getStringSet("groups_$conversation", emptySet()).orEmpty().toMutableSet()
    private var generation = sessionPrefs.getLong("generation",0)
    private var catalog = emptyMap<String, JSONObject>()
    private var published = emptySet<String>()
    private var decisionStarted = false
    private var allowedGroups: Set<String>? = null
    var decisionPlan: AgentDecisionPlan? = null; private set
    val chatOnly: Boolean get() = decisionPlan?.chatOnly == true
    private fun group(name: String) = mcpGroups[name] ?: AgentToolGroups.classify(name)
    private fun description(id: String, names: List<String>): String {
        return AgentToolGroups.groups.firstOrNull { it.id == id }?.summary
            ?: ("MCP 服务器「${mcpLabels[id] ?: id.removePrefix("mcp:")}」 · ${names.size} 个工具 · " +
                names.mapNotNull { catalog[it]?.optJSONObject("function")?.optString("description")?.take(64) }.distinct().take(3).joinToString("；"))
    }
    fun permitted(name: String): Boolean {
        val prefs = AgentFeaturePreferences.load(context)
        return io.github.mangi.eta.agent.prompt.AgentToolPolicyStore.load().allows(name) && (!name.startsWith("recipe_") && name !in setOf("eta_author_tool", "eta_list_recipes") || writable && prefs.optBoolean("recipes_enabled", true))
    }
    fun filterChildTools(tools: JSONArray): JSONArray = JSONArray().also { result ->
        for (i in 0 until tools.length()) {
            val tool = tools.getJSONObject(i)
            val name = tool.getJSONObject("function").getString("name")
            if (permitted(name) && (group(name) == "basic" || allowedGroups?.contains(group(name)) != false)) result.put(tool)
        }
    }

    fun extraSchemas(): JSONArray = JSONArray().also { tools ->
        if (writable && AgentFeaturePreferences.load(context).optBoolean("recipes_enabled", true)) {
            store.invalidate()
            store.schemas(rootAvailable()).forEach(tools::put)
            tools.put(AgentRecipeToolCatalog.authorSchema())
            tools.put(schema("eta_list_recipes", "List persistent tool recipes and their current state", JSONObject().put("name", property("string"))))
        }
    }
    fun project(full: JSONArray): JSONArray {
        val freshGeneration = sessionPrefs.getLong("generation",0)
        if (generation != freshGeneration) { opened.clear(); openedGroups.clear(); generation = freshGeneration }
        catalog = (0 until full.length()).map { full.getJSONObject(it) }
            .associateBy { it.getJSONObject("function").getString("name") }.filterKeys(::permitted)
        if (!decisionStarted) {
            decisionStarted = true
            val groups = catalog.keys.groupBy(::group).filterKeys { it != "basic" }.map { (id, names) ->
                AgentToolGroup(id, description(id, names), names.toSet())
            }
            if (settings.optBoolean("decision_enabled")) {
                val request = io.github.mangi.eta.agent.runtime.AgentRuntimeWire.RunRequest(runId, prompt, config, emptyList(), history, modelSessionId = conversation)
                decisionPlan = AgentDecisionRouter.route(request, config, DecisionTraceLogger, groups, settings, controller)
                val plan = decisionPlan
                if (plan != null && settings.optString("decision_mode", "control") == "control") {
                    allowedGroups = plan.allowedGroups
                    openedGroups += plan.openedGroups
                }
                plan?.failureReason?.let(onDecisionFallback)
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
                name.startsWith("recipe_") || name.startsWith("mcp_") && !settings.optBoolean("mcp_deferred", true)) result.put(tool)
        }
        val visible = (0 until result.length()).map { result.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        val rescue = decisionPlan?.let { it.mode == "control" && it.failureReason == null && it.selectedGroups.isEmpty() && it.openableGroupLimit == null && !chatOnly } == true
        val deferred = (if (rescue) catalog else eligible).filterKeys { it !in visible }
        if (mode != "off" && deferred.isNotEmpty()) {
            val groupIndex = deferred.keys.groupBy(::group).entries.joinToString("; ") { (id, names) -> "$id: ${description(id, names)}" }
            val properties = JSONObject().put("group", property("string").put("enum", JSONArray(deferred.keys.map(::group).distinct())))
            if (mode != "group") properties.put("tools", property("array").put("items", property("string")))
            result.put(schema("tools_use_group", "打开未加载的工具组，下一轮生效。加载不增加权限。$groupIndex", properties))
            if (mode != "group") result.put(schema("tools_search", "搜索尚未加载的工具：关键词会解锁所属组；select:name1,name2 精确加载单个工具。下一轮生效。",
                JSONObject().put("query", property("string")).put("max_results", property("integer")), listOf("query")))
        }
        published = (0 until result.length()).map { result.getJSONObject(it).getJSONObject("function").getString("name") }.toSet()
        return result
    }
    fun execute(call: AgentModelClient.ToolCall, delegate: AgentModelClient.ToolExecutor, requireLoaded: Boolean = true, executionController: AgentRunController = controller): AgentModelClient.ToolResult {
        executionController.throwIfCancelled()
        val args = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrElse { return error("INVALID_ARGUMENT", "参数不是 JSON 对象") }
        if (call.name in setOf("tools_use_group", "tools_search")) {
            if (call.name !in published) return error("LOADER_UNAVAILABLE", "当前加载方式或目录没有这个加载入口")
            if (mode == "group" && (call.name == "tools_search" || args.has("tools"))) return error("GROUP_MODE", "按组模式只支持 group 整组打开")
            val rescue = decisionPlan?.let { it.mode == "control" && it.failureReason == null && it.selectedGroups.isEmpty() && it.openableGroupLimit == null && !chatOnly } == true
            val eligible = catalog.filterKeys { permitted(it) && (rescue || group(it) == "basic" || allowedGroups?.contains(group(it)) != false) }
            val deferred = eligible.filterKeys { it !in published }
            val selected = if (call.name == "tools_search") {
                val candidates = deferred.map { (name, schema) -> AgentToolSearch.Candidate(name,
                    schema.optJSONObject("function")?.optString("description"), null, name.startsWith("mcp_"), group(name)) }
                AgentToolSearch.search(args.optString("query"), candidates, args.optInt("max_results", 5).coerceIn(1, 20)).map { it.name }
            } else if (args.has("tools")) {
                val array = args.getJSONArray("tools"); (0 until array.length()).map { array.getString(it) }
            } else eligible.keys.filter { group(it) == args.optString("group") }
            if (call.name == "tools_use_group" && selected.isEmpty()) return error("GROUP_UNAVAILABLE", "该组不存在、已关闭或本轮被判断模型限制")
            if (selected.any { it !in eligible }) return error("TOOL_NOT_ALLOWED", "目标工具未获用户或判断模型允许")
            if (selected.isEmpty()) return error("NO_MATCHING_TOOLS", "没有匹配的未加载工具；已加载工具不需要搜索，请尝试更宽泛的关键词或整组打开")
            val exact = args.has("tools") || args.optString("query").startsWith("select:", true)
            if (rescue) allowedGroups = allowedGroups.orEmpty() + selected.map(::group)
            if (exact) opened += selected else openedGroups += selected.map(::group)
            if (persistentSession) sessionPrefs.edit().putStringSet("opened_$conversation", opened.toList().takeLast(512).toSet())
                .putStringSet("groups_$conversation", openedGroups.toSet()).apply()
            return AgentModelClient.ToolResult(JSONObject().put("ok", true).put("available_next_round", JSONArray(selected)).toString())
        }
        if (requireLoaded && call.name !in published || !permitted(call.name) ||
            group(call.name) != "basic" && allowedGroups?.contains(group(call.name)) == false) return error("TOOL_NOT_LOADED", "工具未加载、已停用或权限已撤销；先搜索或加载")
        val recipe = if (call.name.startsWith("recipe_")) store.find(call.name.removePrefix("recipe_")) else null
        if (call.name.startsWith("recipe_") && (recipe == null || !recipe.enabled)) return error("RECIPE_DISABLED", "配方未启用")
        if (recipe != null && !rootAvailable() && recipe.name in store.rootDependentRecipes(store.all())) return error("ROOT_REQUIRED", "配方或组合依赖需要 Root")
        executionController.throwIfCancelled()
        if (recipe != null) return try {
            finish(call, AgentModelClient.ToolResult(AgentToolRecipeExecutor(context,
                BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable), store, rootAvailable,
                { step -> checkRecipeStep(step, executionController) }).execute(recipe, args), sensitive = true))
        } catch (failure: Exception) {
            executionController.throwIfCancelled()
            error("RECIPE_EXECUTION_DENIED", failure.message.orEmpty())
        }
        author.execute(call.name, args)?.let { return finish(call,AgentModelClient.ToolResult(it)) }
        return finish(call,delegate.execute(call))
    }
    private fun finish(call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult): AgentModelClient.ToolResult {
        decisionPlan?.let { AgentDecisionMismatch.record(it, conversation, group(call.name), result.content) }
        return result
    }
    private fun checkRecipeStep(step: ToolRecipeStep, executionController: AgentRunController) {
        executionController.throwIfCancelled()
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
        val live = io.github.mangi.eta.config.Prefs.localAgentPreferences()?.let { io.github.mangi.eta.agent.runtime.AgentRuntimePolicy.permissions(it) }
        if (step is ToolRecipeStep.RootShell) check(config.terminalTools && live?.terminalTools != false && rootAvailable()) { "Root 或终端权限不可用" }
        if (step is ToolRecipeStep.SettingsWrite) check(config.deviceSensitiveActionTools && live?.deviceSensitiveActionTools != false) { "设备写入权限已关闭" }
        if (step is ToolRecipeStep.ContentQuery) check(config.deviceSensitiveReadTools && live?.deviceSensitiveReadTools != false) { "设备敏感读取权限已关闭" }
    }
    fun allowsHostedSearch(): Boolean = config.browserTools && io.github.mangi.eta.agent.prompt.AgentToolPolicyStore.load().allows("web_search") && (allowedGroups == null || "web" in allowedGroups.orEmpty())
    fun configForRound(): AgentModelClient.ModelConfig = config.copy(
        hostedWebSearchEnabled = config.hostedWebSearchEnabled && allowsHostedSearch(),
    )
    private fun error(code: String, message: String) = AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", code).put("message", message).toString())
    companion object {
        fun property(type: String) = JSONObject().put("type", type)
        fun schema(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()): JSONObject =
            JSONObject().put("type", "function").put("function", JSONObject().put("name", name).put("description", description)
                .put("parameters", property("object").put("properties", properties).put("required", JSONArray(required))))
    }
}
