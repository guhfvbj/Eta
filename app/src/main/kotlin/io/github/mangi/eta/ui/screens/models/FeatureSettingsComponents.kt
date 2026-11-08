package io.github.mangi.eta.ui.screens.models

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import io.github.mangi.eta.agent.model.AgentFeaturePreferences
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

@Stable internal class FeatureSettingsState(private val context: Context) {
    var value by mutableStateOf(AgentFeaturePreferences.load(context)); private set
    fun set(key: String, entry: Any) { AgentFeaturePreferences.set(key, entry, context); reload() }
    fun reload() { value = AgentFeaturePreferences.load(context) }
}
@Composable internal fun rememberFeatureSettings(): FeatureSettingsState {
    val context = LocalContext.current
    val state = remember(context) { FeatureSettingsState(context) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("eta_agent_features", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.reload() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}
internal data class FeatureModelOption(val id: String, val providerId: String, val provider: String, val name: String, val apiName: String, val baseUrl: String)
@Composable internal fun rememberFeatureModels(): List<FeatureModelOption> {
    var models by remember { mutableStateOf(emptyList<FeatureModelOption>()) }
    LaunchedEffect(Unit) {
        models = withContext(Dispatchers.IO) { ProviderRepository.allProviders().filter { it.isEnabled }.flatMap { provider ->
            provider.models.filter { it.isEnabled && AgentFeaturePreferences.isTextModel(it) }.map {
                FeatureModelOption(it.id, provider.id, provider.name, it.displayName, it.modelId, provider.baseUrl)
            }
        } }
    }
    return models
}
internal fun modelLabel(models: List<FeatureModelOption>, id: String, empty: String = "未选择") =
    models.firstOrNull { it.id == id }?.let { "${it.provider} · ${it.name}" } ?: empty

@Composable internal fun FeatureChoice(title: String, choices: List<Pair<String, String>>, value: String,
    enabled: Boolean = true, summary: String? = null, hints: List<String> = emptyList(), icon: ImageVector? = null,
    onChange: (String) -> Unit) {
    val displayed = if (choices.none { it.second == value }) listOf("当前值：$value" to value) + choices else choices
    EtaDropdownPreference(title, displayed.mapIndexed { index, item -> DropdownItem(text = item.first, summary = hints.getOrNull(index)) },
        displayed.indexOfFirst { it.second == value }.coerceAtLeast(0), enabled = enabled, summary = summary,
        startAction = icon?.let { { EtaPreferenceIcon(it, tint = EtaPreferenceColors.Blue) } }, useWindow = false,
        onSelectedIndexChange = { displayed.getOrNull(it)?.let { choice -> onChange(choice.second) } })
}
@Composable internal fun FeatureModelRow(title: String, models: List<FeatureModelOption>, selected: String,
    enabled: Boolean = true, empty: String = "未选择", clearLabel: String? = null, onSelect: (FeatureModelOption?) -> Unit) {
    var show by remember { mutableStateOf(false) }
    EtaArrowPreference(title, summary = modelLabel(models, selected, empty), enabled = enabled, onClick = { show = true })
    FeatureModelPicker(show, title, models, selected, clearLabel, { show = false }) { selectedModel -> onSelect(selectedModel); show = false }
}
@Composable internal fun FeatureModelPicker(show: Boolean, title: String, models: List<FeatureModelOption>, selected: String,
    clearLabel: String?, onDismiss: () -> Unit, onSelect: (FeatureModelOption?) -> Unit) {
    var search by remember(show) { mutableStateOf("") }
    OverlayBottomSheet(show = show, title = title, onDismissRequest = onDismiss, insideMargin = DpSize(0.dp, 0.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding()) {
            TextField(search, { search = it }, label = "搜索模型或供应商", modifier = Modifier.fillMaxWidth().padding(16.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                if (clearLabel != null) item { EtaSelectPreference(clearLabel, selected.isBlank(), onClick = { onSelect(null) }) }
                val filtered = models.filter { search.isBlank() || "${it.provider} ${it.name} ${it.apiName}".contains(search.trim(), true) }
                filtered.groupBy { it.providerId }.forEach { (_, group) ->
                    item { EtaPreferenceGroupTitle(group.first().provider) }
                    group.forEach { model -> item(key = model.id) {
                        EtaSelectPreference(model.name, selected == model.id, summary = model.apiName, onClick = { onSelect(model) })
                    } }
                }
                if (filtered.isEmpty()) item { Text("没有可用的文本模型", Modifier.padding(24.dp)) }
            }
        }
    }
}
