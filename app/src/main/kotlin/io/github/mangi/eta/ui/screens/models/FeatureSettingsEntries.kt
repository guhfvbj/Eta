package io.github.mangi.eta.ui.screens.models

import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import io.github.mangi.eta.ui.components.*
import io.github.mangi.eta.ui.navigation.AppRoute

@Composable internal fun FeatureModelSettingsEntries(onNavigate: (AppRoute) -> Unit) {
    val settings = rememberFeatureSettings().value
    val models = rememberFeatureModels()
    val decisionLabel = modelLabel(models,settings.optString("decision_model"),"未选择模型")
    EtaPreferenceDivider()
    EtaArrowPreference(stringResource(R.string.decision_model_model_title),summary = when {
        !settings.optBoolean("decision_enabled") -> stringResource(R.string.decision_model_switch_off)
        settings.optBoolean("decision_shadow",true) -> "影子模式 · $decisionLabel"
        else -> "${if(settings.optString("decision_mode","control")=="control") stringResource(R.string.decision_model_mode_control) else stringResource(R.string.decision_model_mode_advise)} · $decisionLabel"
    },startAction = { EtaPreferenceIcon(Icons.Rounded.Psychology,tint = EtaPreferenceColors.Blue) },onClick = { onNavigate(AppRoute.DecisionModel) })
    EtaPreferenceDivider()
    EtaArrowPreference(stringResource(R.string.title_model_title),summary = if(settings.optBoolean("title_custom")) modelLabel(models,settings.optString("title_custom_model_id"))
        else "跟随当前对话模型" + if(settings.optBoolean("title_follow")) " · 每轮刷新" else "",
        startAction = { EtaPreferenceIcon(Icons.Rounded.AutoAwesome,tint = EtaPreferenceColors.Orange) },onClick = { onNavigate(AppRoute.TitleModel) })
}
@Composable internal fun FeaturePromptSettingsEntries() {
    val state = rememberFeatureSettings()
    val settings = state.value
    val models = rememberFeatureModels()
    val modes = listOf("readonly","strict","standard","auto","smart")
    val labels = listOf("只读","严格","标准","自动","智能审查")
    val hints = listOf("仅允许读取","中风险逐个确认","每个会话确认一次","中风险不再询问","逐次模型裁决")
    val summaries = listOf("仅允许读取，其他调用会被拒绝","中风险逐个确认","每个会话确认一次，高风险仍会确认","中风险不再询问，高风险仍会确认","由模型逐次判定中风险以上调用；失败回退标准审查")
    val mode = settings.optString("review_mode","standard")
    EtaPreferenceDivider()
    FeatureChoice("工具调用审查",labels.zip(modes),mode,summary = summaries[modes.indexOf(mode).coerceAtLeast(0)],hints = hints,icon = Icons.Rounded.Security) { state.set("review_mode",it) }
    if(mode == "smart") {
        EtaPreferenceDivider()
        FeatureChoice("审查引擎",listOf("LLM 审查" to "llm","决策模型判断" to "decision"),settings.optString("review_engine","llm"),icon = Icons.Rounded.Psychology,
            summary = "LLM 审查：对话模型逐次判定并输出 allow/deny 结构化结果；决策模型：复用已配置的判断模型（/v1/systemone）。仅智能审查档生效") { state.set("review_engine",it) }
        if(settings.optString("review_engine","llm")=="llm") {
            EtaPreferenceDivider()
            FeatureModelRow("审查模型",models,settings.optString("review_model"),empty = "跟随当前对话模型",clearLabel = "跟随当前对话模型") {
                state.set("review_model",it?.id.orEmpty());state.set("review_provider",it?.providerId.orEmpty())
            }
        }
        EtaPreferenceDivider()
        FeatureChoice("审查超时",listOf(3000,6000,10000,15000,20000).map { "${it/1000} 秒" to it.toString() },settings.optInt("review_timeout",6000).toString(),icon = Icons.Rounded.Timer) { state.set("review_timeout",it.toInt()) }
    }
    EtaPreferenceDivider()
    val loading = settings.optString("loading","search").let { if(it=="tool") "search" else it }
    FeatureChoice("工具渐进加载",listOf("按工具" to "search","按组" to "group","关闭" to "off"),loading,icon = Icons.Rounded.Layers,
        hints = listOf("搜索后解锁所属组","整组一次性打开","发送完整工具目录"),summary = when(loading) {
            "group" -> "用 tools_use_group 整组打开，新建会话后生效"
            "off" -> "发送完整工具目录，新建会话后生效"
            else -> "按工具搜索解锁所属组，新建会话后生效"
        }) { state.set("loading",it) }
    EtaPreferenceDivider()
    EtaSwitchPreference("MCP 工具按需加载",settings.optBoolean("mcp_deferred",true),{ state.set("mcp_deferred",it) },
        summary = "每个 MCP 服务器作为一个延迟组按需加载；关闭后所有服务器的工具 schema 全量常驻",
        startAction = { EtaPreferenceIcon(Icons.Rounded.Dns,tint = EtaPreferenceColors.Blue) })
}
