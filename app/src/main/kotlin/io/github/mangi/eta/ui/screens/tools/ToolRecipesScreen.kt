package io.github.mangi.eta.ui.screens.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.tool.*
import io.github.mangi.eta.ui.components.*
import io.github.mangi.eta.ui.screens.models.rememberFeatureSettings
import kotlinx.coroutines.*
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton

@Composable internal fun ToolRecipesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberFeatureSettings()
    val store = remember { AgentToolRecipeStore.get(context) }
    var recipes by remember { mutableStateOf(store.all()) }
    var editing by remember { mutableStateOf<ToolRecipe?>(null) }
    var menu by remember { mutableStateOf<ToolRecipe?>(null) }
    var deleting by remember { mutableStateOf<ToolRecipe?>(null) }
    var exporting by remember { mutableStateOf<ToolRecipe?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var permissionsExpanded by remember { mutableStateOf(false) }
    fun refresh() { store.invalidate(); recipes = store.all() }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val recipe = exporting
        exporting = null
        if(uri != null && recipe != null) scope.launch {
            message = withContext(Dispatchers.IO) { runCatching {
                val payload = ToolRecipeShareFormat.encode(listOf(recipe))
                requireNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(payload) }
                "配方已导出"
            }.getOrElse { "导出失败：${it.message}" } }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            message = withContext(Dispatchers.IO) { runCatching {
                val raw = requireNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use {
                    val chars = CharArray(ToolRecipeShareFormat.MAX_PAYLOAD_CHARS + 1)
                    var count = 0
                    while(count < chars.size) { val read = it.read(chars,count,chars.size-count); if(read<0) break; count += read }
                    require(count <= ToolRecipeShareFormat.MAX_PAYLOAD_CHARS) { "文件过大" }
                    String(chars,0,count)
                }
                "已导入 ${store.importShared(raw)} 份配方，默认停用，请审计后启用。"
            }.getOrElse { "导入失败：${it.message}" } }
            refresh()
        }
    }
    val editor = editing
    if(editor != null) {
        RecipeEditPage(editor, recipes, store, onBack = { editing=null }, onSaved = { editing=null; refresh() }, onError = { message=it })
    } else MiuixScaffoldPage(title = "模型自建工具",onBack = onBack,actions = {
        TextButton("刷新",onClick = { refresh() })
        TextButton("导入配方",onClick = { importer.launch(arrayOf("application/json","text/plain")) })
    }) {
        item { EtaPreferenceGroup { EtaActionPreference("模型自建工具",summary = "配方是模型用固定声明式步骤写出来的工具。只有启用后才会执行，保存后也要到下一轮请求才会出现在模型的工具列表里。") } }
        item {
            EtaPreferenceGroup {
                EtaSwitchPreference("启用工具配方",state.value.optBoolean("recipes_enabled",true),{ state.set("recipes_enabled",it) })
                EtaPreferenceDivider()
                EtaExpandPreference("配方步骤权限",permissionsExpanded,summary = "HTTP、文件、Root、系统设置与 ContentProvider",onClick = { permissionsExpanded=!permissionsExpanded })
                if(permissionsExpanded) listOf("recipe_http" to "HTTP 请求","recipe_files" to "应用可访问的文件读写","recipe_root" to "Root Shell","recipe_settings" to "系统设置写入","recipe_content" to "ContentProvider 查询").forEach { (key,label) ->
                    EtaSwitchPreference(label,state.value.optBoolean(key,key in setOf("recipe_http","recipe_files")),{ state.set(key,it) },
                        summary = if(key == "recipe_root") "同时需要终端能力与设备 Root 授权；危险步骤仍经过工具审查" else null)
                }
            }
        }
        item { EtaPreferenceGroupTitle("全部配方 · ${recipes.size}") }
        if(recipes.isEmpty()) item { EtaPreferenceGroup { EtaActionPreference("还没有配方", summary = "让模型做一件它现有工具做不到的事，它就能用 eta_author_tool 写出一份配方，之后会出现在这里。") } }
        val rootNames = store.rootDependentRecipes(recipes)
        recipes.forEach { recipe -> item(key = recipe.name) {
            EtaPreferenceGroup {
                EtaPreference(title = recipe.name, startAction = { EtaPreferenceIcon(Icons.Rounded.Extension,tint = EtaPreferenceColors.Blue) },
                    summary = "${recipe.description}\n${recipe.steps.size} 个步骤" + if(recipe.name in rootNames) " · 需要 Root" else "",
                    endActions = {
                        TextButton("⋯",onClick = { menu=recipe })
                        EtaSwitch(recipe.enabled, { enabled -> runCatching { check(store.setEnabled(recipe.name,enabled) != null); refresh() }.onFailure { message=it.message } })
                    }, onClick = { editing=recipe })
            }
        } }
    }
    menu?.let { recipe -> EtaWindowDialog(show = true,title = recipe.name,onDismissRequest = { menu=null }) {
        TextButton("编辑",onClick = { editing=recipe;menu=null })
        TextButton("分享",onClick = { exporting=recipe;menu=null;export.launch("${recipe.name}-${System.currentTimeMillis()}.eta-recipes.json") })
        TextButton("删除",onClick = { deleting=recipe;menu=null })
        TextButton("取消",onClick = { menu=null })
    } }
    deleting?.let { recipe -> EtaWindowDialog(show = true,title = "删除 ${recipe.name}？",summary = "删除后无法撤销；依赖它的组合配方将无法执行。",onDismissRequest = { deleting=null }) {
        TextButton("取消",onClick = { deleting=null })
        TextButton("删除",onClick = { runCatching { check(store.delete(recipe.name));refresh() }.onFailure { message=it.message };deleting=null })
    } }
    message?.let { text -> EtaWindowDialog(show = true,title = "工具配方",summary = text,onDismissRequest = { message=null }) {
        TextButton("知道了",onClick = { message=null })
    } }
}

@Composable private fun RecipeEditPage(recipe: ToolRecipe, all: List<ToolRecipe>, store: AgentToolRecipeStore,
    onBack: () -> Unit,onSaved: () -> Unit,onError: (String) -> Unit) {
    var name by remember(recipe.name) { mutableStateOf(recipe.name) }
    var description by remember(recipe.name) { mutableStateOf(recipe.description) }
    var enabled by remember(recipe.name) { mutableStateOf(recipe.enabled) }
    val valid = Regex("^[a-z][a-z0-9_]{2,39}$").matches(name) && description.isNotBlank() && description.length <= ToolRecipeConstants.MAX_DESCRIPTION_LENGTH &&
        name !in ToolRecipeReservedNames.RESERVED && io.github.mangi.eta.agent.model.AgentToolGroups.groups.none { name in it.toolNames } &&
        (name == recipe.name || all.none { it.name.equals(name,true) }) &&
        name !in AgentToolRequirements.toolNames && "recipe_$name" !in AgentToolRequirements.toolNames
    val root = recipe.name in store.rootDependentRecipes(all)
    MiuixScaffoldPage(title = "编辑配方",onBack = onBack,actions = { TextButton("保存",enabled = valid,onClick = {
        when(val result = store.rename(recipe.name,recipe.copy(name=name,description=description,enabled=enabled))) {
            is AgentToolRecipeStore.SaveResult.Ok -> onSaved()
            is AgentToolRecipeStore.SaveResult.Failed -> onError(result.message)
        }
    }) }) {
        item { EtaPreferenceGroup {
            EtaSwitchPreference("启用",enabled,{ enabled=it })
            TextField(name,{ name=it },label = "名称",modifier = Modifier.fillMaxWidth().padding(16.dp))
            if(!valid) Text("名称须为 3–40 个小写字母、数字或下划线，字母开头，且不能重名；描述须为 1–1200 字符。",Modifier.padding(horizontal=16.dp))
            TextField(description,{ description=it },label = "它做什么",modifier = Modifier.fillMaxWidth().padding(16.dp))
        } }
        item { EtaPreferenceGroupTitle("步骤 · ${recipe.steps.size}") }
        recipe.steps.forEachIndexed { index, step -> item(key = "step-$index") {
            EtaPreferenceGroup { EtaActionPreference("${index+1}. ${step.op}",summary = stepSummary(step),
                startAction = { EtaPreferenceIcon(stepIcon(step),tint = EtaPreferenceColors.Blue) }) }
        } }
        val properties = recipe.parameters.optJSONObject("properties") ?: JSONObject()
        val required = recipe.parameters.optJSONArray("required")?.let { (0 until it.length()).map(it::getString).toSet() }.orEmpty()
        item { EtaPreferenceGroupTitle("入参 · ${properties.length()}") }
        if(properties.length()==0) item { EtaPreferenceGroup { EtaActionPreference("无入参") } }
        properties.keys().asSequence().forEach { parameter -> item(key = "param-$parameter") {
            val schema = properties.optJSONObject(parameter) ?: JSONObject()
            EtaPreferenceGroup { EtaActionPreference(parameter,summary = "${schema.optString("type")} · ${if(parameter in required) "必填" else "可选"}\n${schema.optString("description")}") }
        } }
        item { EtaPreferenceGroupTitle("工具信息"); EtaPreferenceGroup {
            EtaActionPreference("模型侧名称",summary = "recipe_$name")
            EtaPreferenceDivider()
            EtaActionPreference("Root 依赖",summary = if(root) "需要 Root" else "不需要 Root")
        } }
    }
}
private fun stepIcon(step: ToolRecipeStep): ImageVector = when(step) {
    is ToolRecipeStep.RootShell -> Icons.Rounded.Terminal
    is ToolRecipeStep.HttpRequest -> Icons.Rounded.Language
    is ToolRecipeStep.SettingsRead,is ToolRecipeStep.SettingsWrite -> Icons.Rounded.Settings
    is ToolRecipeStep.FileRead,is ToolRecipeStep.FileWrite -> Icons.Rounded.Description
    is ToolRecipeStep.ContentQuery -> Icons.Rounded.Storage
    is ToolRecipeStep.Compose -> Icons.Rounded.Layers
    is ToolRecipeStep.Literal -> Icons.Rounded.TextFields
}
internal fun stepSummary(step: ToolRecipeStep): String = when(step) {
    is ToolRecipeStep.RootShell -> "以 Root 执行：${step.command}\n超时 ${step.timeoutMillis} ms"
    is ToolRecipeStep.HttpRequest -> "HTTP ${step.method} ${step.url}\nHeaders: ${step.headers}\n${step.body.orEmpty()}"
    is ToolRecipeStep.SettingsRead -> "读取系统设置：${step.namespace}/${step.key}"
    is ToolRecipeStep.SettingsWrite -> "写入系统设置：${step.namespace}/${step.key} = ${step.value}"
    is ToolRecipeStep.FileRead -> "读取文件：${step.path}\n最多 ${step.maxBytes} 字节"
    is ToolRecipeStep.FileWrite -> "${if(step.append) "追加" else "写入"}文件：${step.path}\n${step.content}"
    is ToolRecipeStep.ContentQuery -> "查询：${step.uri}\nprojection=${step.projection}\nselection=${step.selection}\nlimit=${step.limit}"
    is ToolRecipeStep.Compose -> "组合配方：recipe_${step.recipe}\n${if(step.passOutput) "传递上一步输出" else "仅传递原参数"}"
    is ToolRecipeStep.Literal -> "输出文本：${step.text}"
}
