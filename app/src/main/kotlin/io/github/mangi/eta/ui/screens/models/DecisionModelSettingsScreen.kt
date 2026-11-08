package io.github.mangi.eta.ui.screens.models

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.decision.*
import io.github.mangi.eta.agent.model.AgentFeaturePreferences
import io.github.mangi.eta.agent.tool.AgentToolReviewCoordinator
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.tool.AgentToolAuthorization
import io.github.mangi.eta.ui.components.*
import kotlinx.coroutines.*
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextButton

@Composable internal fun DecisionModelSettingsScreen(onBack: () -> Unit) {
    val state = rememberFeatureSettings()
    val settings = state.value
    val models = rememberFeatureModels()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabled = settings.optBoolean("decision_enabled")
    val shadow = settings.optBoolean("decision_shadow", true)
    val mode = settings.optString("decision_mode", "control")
    var endpointConfig by remember { mutableStateOf<io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig?>(null) }
    LaunchedEffect(settings.optString("decision_model")) {
        endpointConfig = withContext(Dispatchers.IO) { runCatching { AgentFeaturePreferences.model(settings.optString("decision_model")) }.getOrNull() }
    }
    var endpointEditor by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<AgentDecisionConnectivity.Result?>(null) }
    var probeExpanded by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf(AgentDecisionDiagnostics.last) }
    var telemetry by remember { mutableStateOf(AgentDecisionTelemetry.globalSnapshot()) }
    LaunchedEffect(Unit) { while (true) { last = AgentDecisionDiagnostics.last; telemetry = AgentDecisionTelemetry.globalSnapshot(); delay(1000) } }
    fun switch(key: String, value: Boolean) = state.set(key, value)
    MiuixScaffoldPage(title = stringResource(R.string.decision_model_model_title), onBack = onBack) {
        item(key = "status") {
            EtaPreferenceGroup {
                EtaActionPreference(title = when {
                    !enabled -> stringResource(R.string.decision_model_disabled_notice_title)
                    shadow -> stringResource(R.string.decision_model_shadow_notice_title)
                    settings.optString("decision_model").isBlank() -> stringResource(R.string.decision_model_missing_credentials_title)
                    else -> if (mode == "control") "控制模式正在生效" else "建议模式正在记录"
                }, summary = when {
                    !enabled -> stringResource(R.string.decision_model_disabled_notice_summary)
                    shadow -> stringResource(R.string.decision_model_shadow_notice_summary)
                    settings.optString("decision_model").isBlank() -> "还没有选择判断模型；每轮恢复用户允许的工具，并在 EtaTrace 记录警告。"
                    else -> "判断结果、耗时、升级与降级原因写入 EtaTrace，可在本页查看进程内统计。"
                })
            }
        }
        item(key = "master") {
            EtaPreferenceGroupTitle(stringResource(R.string.decision_model_group_master))
            EtaPreferenceGroup { EtaSwitchPreference(stringResource(R.string.decision_model_enabled_title), enabled,
                { switch("decision_enabled", it) }, summary = stringResource(R.string.decision_model_enabled_summary)) }
        }
        item(key = "mode") {
            EtaPreferenceGroupTitle(stringResource(R.string.decision_model_group_mode))
            EtaPreferenceGroup {
                FeatureChoice(stringResource(R.string.decision_model_mode_title), listOf(stringResource(R.string.decision_model_mode_control) to "control", stringResource(R.string.decision_model_mode_advise) to "advise"), mode, enabled,
                    summary = "控制模式收窄工具组；建议模式只记录建议" + if(shadow) " · 影子模式已开启，本项仅记录日志" else "") { state.set("decision_mode", it) }
                EtaPreferenceDivider()
                EtaSwitchPreference(stringResource(R.string.decision_model_shadow_title), shadow, { switch("decision_shadow", it) },
                    enabled = enabled, summary = stringResource(R.string.decision_model_shadow_summary))
            }
        }
        item(key = "model") {
            EtaPreferenceGroupTitle(stringResource(R.string.decision_model_group_model))
            EtaPreferenceGroup {
                FeatureModelRow(stringResource(R.string.decision_model_model_title), models, settings.optString("decision_model"), enabled) { model ->
                    state.set("decision_model", model?.id.orEmpty()); state.set("decision_provider", model?.providerId.orEmpty())
                }
                EtaPreferenceDivider()
                FeatureChoice("决策协议", listOf("SystemOne (/v1/systemone)" to "systemone", "普通 LLM JSON" to "llm"), settings.optString("decision_protocol", "llm"), enabled) { state.set("decision_protocol", it) }
                EtaPreferenceDivider()
                EtaArrowPreference(stringResource(R.string.decision_model_endpoint_title), summary = settings.optString("decision_endpoint").ifBlank { "从判断模型的供应商地址推导" }, enabled = enabled,
                    onClick = { endpointEditor = settings.optString("decision_endpoint") })
                EtaPreferenceDivider()
                FeatureChoice(stringResource(R.string.decision_model_timeout_title), listOf(1000,2000,3000,5000,10000).map { "${it/1000} 秒" to it.toString() },
                    settings.optInt("decision_timeout",3000).toString(), enabled) { state.set("decision_timeout",it.toInt()) }
            }
        }
        item(key = "connectivity") {
            EtaPreferenceGroupTitle(stringResource(R.string.decision_model_group_connectivity))
            EtaPreferenceGroup {
                EtaActionPreference(stringResource(R.string.decision_model_connectivity_title), actionLabel = if(probing) stringResource(R.string.decision_model_connectivity_running) else "测试", enabled = enabled && !probing,
                    summary = "使用当前配置发送最小请求，不影响缓存与统计", onClick = {
                        probing = true
                        scope.launch { try { probe = withContext(Dispatchers.IO) { runCatching { AgentDecisionConnectivity.probeDecisionConnectivity(state.value, AgentRunController()) }
                            .getOrElse { AgentDecisionConnectivity.Result(false,null,0,"","","配置或连接失败：${it.javaClass.simpleName}") } } }
                            finally { probing = false } }
                    })
                probe?.let { result ->
                    EtaPreferenceDivider()
                    EtaActionPreference(if(result.ok) stringResource(R.string.decision_model_connectivity_ok_title) else stringResource(R.string.decision_model_connectivity_fail_title), summary = "HTTP ${result.http ?: "—"} · ${result.latencyMs} ms\n${result.detail}\n${java.time.Instant.ofEpochMilli(result.at)}")
                    EtaExpandPreference(if(probeExpanded) stringResource(R.string.decision_model_connectivity_details_hide) else stringResource(R.string.decision_model_connectivity_details_show), probeExpanded, enabled = enabled, onClick = { probeExpanded = !probeExpanded })
                    if(probeExpanded) Text("URL ${result.endpoint}\nmodel ${result.model}\n${result.detail}", Modifier.padding(16.dp))
                }
            }
        }
        item(key = "advanced") {
            EtaPreferenceGroupTitle(stringResource(R.string.decision_model_group_advanced))
            EtaPreferenceGroup {
                EtaSwitchPreference(stringResource(R.string.decision_model_escalation_enabled_title),settings.optBoolean("decision_escalation_enabled"), { switch("decision_escalation_enabled",it) }, enabled = enabled,
                    summary = stringResource(R.string.decision_model_escalation_enabled_summary))
                EtaPreferenceDivider()
                FeatureModelRow(stringResource(R.string.decision_model_escalation_model_title),models,settings.optString("decision_upgrade_model"),enabled && settings.optBoolean("decision_escalation_enabled"),
                    empty = stringResource(R.string.decision_model_escalation_model_not_selected), clearLabel = "沿用判断模型") { model ->
                    state.set("decision_upgrade_model",model?.id.orEmpty()); state.set("decision_upgrade_provider",model?.providerId.orEmpty())
                }
                EtaPreferenceDivider()
                FeatureChoice(stringResource(R.string.decision_model_escalation_confidence_title),listOf("关闭" to "0","40%" to "40","60%" to "60","80%" to "80"),
                    settings.optInt("decision_confidence_pct",60).toString(),enabled) { state.set("decision_confidence_pct",it.toInt()) }
                EtaPreferenceDivider()
                FeatureChoice(stringResource(R.string.decision_model_failure_policy_title),listOf(stringResource(R.string.decision_model_failure_open) to "open",stringResource(R.string.decision_model_failure_closed) to "closed"),settings.optString("decision_failure","open"),enabled) { state.set("decision_failure",it) }
                EtaPreferenceDivider()
                FeatureChoice(stringResource(R.string.decision_model_history_turns_title),listOf(0,1,3,5,10).map { (if(it==0) stringResource(R.string.decision_model_turns_current_only) else "$it 轮") to it.toString() },
                    settings.optInt("decision_history",3).toString(),enabled) { state.set("decision_history",it.toInt()) }
                EtaPreferenceDivider()
                EtaSwitchPreference(stringResource(R.string.decision_model_empty_group_rescue_title),settings.optBoolean("decision_empty_group_rescue",true), { switch("decision_empty_group_rescue",it) },
                    enabled = enabled && mode=="control" && !shadow, summary = "需要工具却未选中组时保留加载入口，仍受用户权限与白名单限制")
            }
        }
        item(key = "diagnostics") {
            EtaPreferenceGroupTitle(stringResource(R.string.decision_model_group_diagnostics))
            EtaPreferenceGroup {
                EtaExpandPreference(stringResource(R.string.decision_model_diagnostics_title), diagnosticsExpanded, enabled = enabled,
                    summary = last?.let { "selected=${it.selected} · allowed=${it.allowed} · 关闭段=${it.disabledSections} · ${it.latencyMs} ms · token=${it.usage}" } ?: stringResource(R.string.decision_model_diagnostics_empty),
                    onClick = { diagnosticsExpanded = !diagnosticsExpanded })
                if(diagnosticsExpanded) Text("判断 ${telemetry.decisions} 次 · 升级 ${telemetry.escalations} 次 · 缓存命中 ${(telemetry.cacheHitRate*100).toInt()}% · 降级 ${telemetry.degraded} 次 · 漏判 ${telemetry.mismatches} 次\n升级原因 ${telemetry.escalationReasons}\n降级原因 ${telemetry.degradedReasons}\n最近降级 ${last?.failureReason ?: "无"}",Modifier.padding(16.dp))
            }
        }
        item(key = "cleanup") {
            EtaPreferenceGroupTitle(stringResource(R.string.decision_model_group_cleanup))
            EtaPreferenceGroup { EtaActionPreference(stringResource(R.string.decision_model_cleanup_title), summary = "清空判断缓存、粘性组、遥测及已打开工具会话缓存", tone = EtaActionTone.Danger,
                enabled = enabled, onClick = { confirmClear = true }) }
        }
    }
    endpointEditor?.let { input ->
        val base = models.firstOrNull { it.id == settings.optString("decision_model") }?.baseUrl.orEmpty()
        val resolution = if(input.isBlank() && settings.optString("decision_protocol","llm") == "llm" && endpointConfig != null) {
            AgentDecisionEndpoint.requestUrl(endpointConfig!!,input,"llm")?.let { AgentDecisionEndpoint.Resolution.Success(it) }
                ?: AgentDecisionEndpoint.Resolution.Invalid(AgentDecisionEndpoint.REASON_MALFORMED)
        } else AgentDecisionEndpoint.normalize(base,input)
        EtaWindowDialog(show = true,title = stringResource(R.string.decision_model_endpoint_title),onDismissRequest = { endpointEditor = null }) {
            TextField(input,{ endpointEditor = it },label = "HTTPS 请求地址（可留空）",modifier = Modifier.fillMaxWidth())
            Text(when(resolution) { is AgentDecisionEndpoint.Resolution.Success -> "请求地址：${endpointConfig?.let { AgentDecisionEndpoint.requestUrl(it,input,settings.optString("decision_protocol","llm")) } ?: resolution.url}"; is AgentDecisionEndpoint.Resolution.Invalid -> "地址校验：${resolution.reason}" })
            TextButton("保存", enabled = input.isBlank() || resolution is AgentDecisionEndpoint.Resolution.Success, onClick = { state.set("decision_endpoint",input.trim()); endpointEditor = null })
            TextButton("取消",onClick = { endpointEditor = null })
        }
    }
    if(confirmClear) EtaWindowDialog(show = true,title = "清理缓存与遥测？",summary = "设置保持不变；判断缓存、粘性、遥测与工具会话缓存将被清空。",onDismissRequest = { confirmClear=false }) {
        TextButton("取消",onClick = { confirmClear=false })
        TextButton(stringResource(R.string.decision_model_group_cleanup),onClick = { AgentDecisionRouter.clear(); AgentFeaturePreferences.clearToolSessions(context); AgentToolReviewCoordinator.clearCache(); AgentToolAuthorization.clearGrants(); confirmClear=false })
    }
}
