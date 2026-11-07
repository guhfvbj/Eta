package io.github.mangi.eta.ui.screens.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.tool.*
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AgentFeatureSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf(AgentFeaturePreferences.load(context)) }
    var models by remember { mutableStateOf(listOf("" to "跟随当前对话模型")) }
    val store = remember { RecipeRuntime.store(context) }
    var recipes by remember { mutableStateOf(store.all()) }
    var editor by remember { mutableStateOf<String?>(null) }
    var originalName by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun set(key: String, value: Any) {
        AgentFeaturePreferences.set(key, value, context)
        settings = AgentFeaturePreferences.load(context)
    }
    fun refresh() { store.invalidate(); recipes = store.all() }
    LaunchedEffect(Unit) {
        models = listOf("" to "跟随当前对话模型") + withContext(Dispatchers.IO) {
            ProviderRepository.allProviders().filter { it.isEnabled }.flatMap { provider ->
                provider.models.filter { it.isEnabled }.map { it.id to "${provider.name} · ${it.displayName}" }
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val payload = ToolRecipeShareFormat.encode(store.all())
                    requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(payload) }
                    "配方已导出"
                }.getOrElse { it.message ?: "导出失败" }
            }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) {
                runCatching {
                    val raw = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use {
                        val chars = CharArray(ToolRecipeShareFormat.MAX_PAYLOAD_CHARS + 1)
                        var count = 0
                        while (count < chars.size) {
                            val read = it.read(chars, count, chars.size - count)
                            if (read < 0) break
                            count += read
                        }
                        require(count <= ToolRecipeShareFormat.MAX_PAYLOAD_CHARS) { "配方文件过大" }
                        String(chars, 0, count)
                    }
                    "已导入 ${store.importShared(raw)} 份配方，默认停用，请检查后启用"
                }.getOrElse { it.message ?: "导入失败" }
            }
            refresh()
        }
    }
    MiuixScaffoldPage(title = "模型与工具策略", onBack = onBack) {
        item {
            EtaPreferenceGroupTitle("工具按需加载")
            EtaPreferenceGroup {
                FeatureChoice("加载方式", listOf("按单个工具" to "tool", "按分组" to "group", "全量加载" to "off"), settings.optString("loading", "tool")) { set("loading", it) }
                Text("加载方式在新会话生效；禁用工具和分组会立即阻止执行。", Modifier.padding(16.dp))
                EtaSwitchPreference("MCP 按服务器延迟加载", settings.optBoolean("mcp_deferred", true), { set("mcp_deferred", it) })
                AgentToolGroups.groups.forEach { group ->
                    EtaSwitchPreference(group.summary, settings.optBoolean("group_${group.id}", true), { set("group_${group.id}", it) })
                }
                TextField(value = settings.optString("disabled_tools"), onValueChange = { set("disabled_tools", it) },
                    label = "禁用的工具名称（逗号分隔，含 MCP 精确名称）", modifier = Modifier.fillMaxWidth().padding(12.dp))
            }
        }
        item {
            EtaPreferenceGroupTitle("独立标题模型")
            EtaPreferenceGroup {
                EtaSwitchPreference("自动生成会话标题", settings.optBoolean("title_enabled", true), { set("title_enabled", it) })
                FeatureModel("标题生成模型", models, settings.optString("title_model")) { set("title_model", it) }
                EtaSwitchPreference("每轮更新标题", settings.optBoolean("title_follow"), { set("title_follow", it) }, summary = "手动改名的会话会永久保护，生成失败保留原标题。")
            }
        }
        item {
            EtaPreferenceGroupTitle("判断模型")
            EtaPreferenceGroup {
                EtaSwitchPreference("启用判断模型", settings.optBoolean("decision_enabled"), { set("decision_enabled", it) })
                EtaSwitchPreference("影子模式", settings.optBoolean("decision_shadow", true), { set("decision_shadow", it) }, summary = "只记录判断结果，不改变主模型的工具目录。")
                FeatureModel("判断模型（需明确选择）", models, settings.optString("decision_model")) { set("decision_model", it) }
                FeatureChoice("决策协议", listOf("普通 LLM JSON" to "llm", "SystemOne" to "systemone"), settings.optString("decision_protocol", "llm")) { set("decision_protocol", it) }
                FeatureChoice("工作模式", listOf("建议：提前加载" to "advise", "管控：限制工具组" to "control"), settings.optString("decision_mode", "advise")) { set("decision_mode", it) }
                FeatureModel("升级复判模型（不选则关闭）", models, settings.optString("decision_upgrade_model")) { set("decision_upgrade_model", it) }
                FeatureChoice("升级置信度阈值", listOf("50%" to "0.5", "70%" to "0.7", "90%" to "0.9"), settings.optDouble("decision_confidence", 0.7).toString()) { set("decision_confidence", it.toDouble()) }
                FeatureChoice("决策超时", listOf("5 秒" to "5000", "15 秒" to "15000", "30 秒" to "30000"), settings.optInt("decision_timeout", 15000).toString()) { set("decision_timeout", it.toInt()) }
                TextField(value = settings.optString("decision_endpoint"), onValueChange = { set("decision_endpoint", it) }, label = "SystemOne 自定义 HTTPS 端点（可留空）", modifier = Modifier.fillMaxWidth().padding(12.dp))
                FeatureChoice("判断失败回退", listOf("恢复用户允许的工具" to "open", "仅保留基础工具" to "closed"), settings.optString("decision_failure", "open")) { set("decision_failure", it) }
            }
        }
        item {
            EtaPreferenceGroupTitle("工具审查与授权")
            EtaPreferenceGroup {
                FeatureChoice("审查模式", listOf("只读" to "readonly", "严格逐次确认" to "strict", "标准分级授权" to "standard", "智能审查" to "smart", "自动（致命操作仍确认）" to "auto"), settings.optString("review_mode", "standard")) { set("review_mode", it) }
                if (settings.optString("review_mode") == "smart") {
                    FeatureChoice("审查引擎", listOf("普通 LLM JSON" to "llm", "SystemOne" to "systemone"), settings.optString("review_engine", "llm")) { set("review_engine", it) }
                    FeatureModel("审查模型", models, settings.optString("review_model")) { set("review_model", it) }
                }
                Text("智能审查失败回退规则确认；高风险操作仍需用户授权。中风险授权只在本次运行内对相同参数有效。Root 配方始终逐次确认。未响应的授权会超时拒绝。", Modifier.padding(16.dp))
            }
        }
        item {
            EtaPreferenceGroupTitle("模型自建工具")
            EtaPreferenceGroup {
                EtaSwitchPreference("启用工具配方", settings.optBoolean("recipes_enabled", true), { set("recipes_enabled", it) })
                listOf("recipe_http" to "HTTP 请求", "recipe_files" to "应用可访问的文件读写", "recipe_root" to "Root Shell", "recipe_settings" to "系统设置写入", "recipe_content" to "ContentProvider 查询").forEach { (key, label) ->
                    EtaSwitchPreference(label, settings.optBoolean(key, key in setOf("recipe_http", "recipe_files")), { set(key, it) })
                }
                EtaTextButton("新建配方", { originalName = null; editor = "{\"name\":\"my_tool\",\"description\":\"工具说明\",\"parameters\":{\"type\":\"object\",\"properties\":{}},\"steps\":[{\"op\":\"literal\",\"text\":\"Hello\"}]}" })
                EtaTextButton("导入配方", { import.launch(arrayOf("application/json", "text/plain")) })
                EtaTextButton("导出全部配方", { export.launch("Eta-tool-recipes.json") })
                recipes.forEach { recipe ->
                    EtaSwitchPreference(recipe.name, recipe.enabled, { store.setEnabled(recipe.name, it); refresh() }, summary = recipe.description,
                        bottomAction = { EtaTextButton("编辑定义与步骤", { originalName = recipe.name; editor = ToolRecipeShareFormat.entryOf(recipe).toString(2) }) })
                }
            }
        }
    }
    editor?.let { raw ->
        WindowDialog(show = true, title = "编辑工具配方", onDismissRequest = { editor = null }) {
            TextField(value = raw, onValueChange = { editor = it }, modifier = Modifier.fillMaxWidth(), maxLines = 12)
            TextButton(text = "保存", onClick = {
                message = runCatching {
                    val parsed = ToolRecipeParser.parse(JSONObject(raw), store.all().map { it.name }.toSet())
                    val recipe = (parsed as? ToolRecipeParser.ParseResult.Ok)?.value ?: error((parsed as? ToolRecipeParser.ParseResult.Invalid)?.message ?: "定义无效")
                    val prospective = store.all().filterNot { it.name == originalName } + recipe
                    require(store.detectCycle(prospective) == null) { "组合链包含循环" }
                    val saved = if (originalName == null) { require(!store.exists(recipe.name)) { "名称已存在" }; store.save(recipe.copy(enabled = false)) }
                        else store.rename(originalName!!, recipe.copy(enabled = store.find(originalName!!)?.enabled ?: false))
                    if (saved is AgentToolRecipeStore.SaveResult.Failed) error(saved.message)
                    editor = null; refresh(); "配方已保存"
                }.getOrElse { it.message ?: "保存失败" }
            })
            originalName?.let { name -> TextButton(text = "删除此配方", onClick = { store.delete(name); editor = null; refresh() }) }
            TextButton(text = "取消", onClick = { editor = null })
        }
    }
    message?.let { text -> WindowDialog(show = true, title = "工具策略", summary = text, onDismissRequest = { message = null }) {
        TextButton(text = "知道了", onClick = { message = null })
    } }
}

@Composable private fun FeatureChoice(title: String, choices: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) {
    EtaOverlayDropdownPreference(title = title, items = choices.map { it.first }, selectedIndex = choices.indexOfFirst { it.second == value }.coerceAtLeast(0),
        onSelectedIndexChange = { choices.getOrNull(it)?.let { choice -> onChange(choice.second) } })
}
@Composable private fun FeatureModel(title: String, choices: List<Pair<String, String>>, value: String, onChange: (String) -> Unit) =
    FeatureChoice(title, choices.map { it.second to it.first }, value, onChange)

@Composable internal fun AgentToolAuthorizationHost() {
    val requests by AgentToolAuthorization.pending.collectAsState()
    requests.firstOrNull()?.let { request ->
        WindowDialog(show = true, title = "确认工具操作 · ${request.risk.level}",
            summary = "会话：${request.conversation}\n工具：${request.tool}\n${request.risk.reasons.joinToString("\n")}\n参数：${request.arguments.take(3000)}",
            onDismissRequest = { AgentToolAuthorization.answer(request.id, false) }) {
            TextButton(text = "拒绝", onClick = { AgentToolAuthorization.answer(request.id, false) })
            TextButton(text = if (request.repeatAllowed) "允许本次运行中的相同操作" else "允许这次操作", onClick = { AgentToolAuthorization.answer(request.id, true) })
        }
    }
}
