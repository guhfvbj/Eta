package io.github.mangi.eta.ui.screens.models

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Group as CouiPreferenceGroup
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaActionPreference
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaScaffoldPage as MiuixScaffoldPage
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiIconDivider
import io.github.mangi.eta.ui.components.coui.CouiLazyScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSelectPreference
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField

@Composable
internal fun CompactionModelSettingsScreen(onBack: () -> Unit) {
    val state = rememberFeatureSettings()
    val settings = state.value
    val models = rememberFeatureModels()
    val custom = settings.optBoolean("compaction_custom")
    val selected = settings.optString("compaction_custom_model_id")
    val providerId = settings.optString("compaction_custom_provider_id")
    val selectedModels = models.filter { it.providerId == providerId }
    val title = stringResource(R.string.compaction_model_title)
    val follow = stringResource(R.string.compaction_model_follow_current)
    val summary = modelLabel(selectedModels, selected, follow)
    var showPicker by remember { mutableStateOf(false) }
    val toggle: (Boolean) -> Unit = { enabled ->
        // 首次打开先选择有效模型，避免把缺少引用的 true 写进设置后被归一化关闭。
        if (enabled && (selected.isBlank() || providerId.isBlank())) showPicker = true
        else state.set("compaction_custom", enabled)
    }
    val select: (FeatureModelOption?) -> Unit = { model ->
        state.set("compaction_custom", false)
        state.set("compaction_custom_provider_id", model?.providerId.orEmpty())
        state.set("compaction_custom_model_id", model?.id.orEmpty())
        state.set("compaction_custom", model != null)
        showPicker = false
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            if (showPicker) {
                CouiCompactionModelPicker(models, selected, providerId, { showPicker = false }, select)
            } else {
                CouiScaffoldPage(title = title, onBack = onBack) {
                    CouiPreferenceGroup {
                        CouiPreference(title = stringResource(R.string.settings_auto_compaction), summary = stringResource(R.string.compaction_model_description))
                    }
                    CouiPreferenceGroup {
                        CouiSwitchPreference(title = stringResource(R.string.compaction_model_custom),
                            checked = custom, onCheckedChange = toggle,
                            summary = stringResource(R.string.compaction_model_custom_summary))
                        CouiIconDivider()
                        CouiPreference(title = title, summary = summary, enabled = custom, onClick = { showPicker = true })
                    }
                }
            }
        }
        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(title = title, onBack = onBack) {
                item {
                    EtaPreferenceGroup {
                        EtaActionPreference(stringResource(R.string.settings_auto_compaction), summary = stringResource(R.string.compaction_model_description))
                    }
                }
                item {
                    EtaPreferenceGroup {
                        EtaSwitchPreference(stringResource(R.string.compaction_model_custom), custom, toggle,
                            summary = stringResource(R.string.compaction_model_custom_summary))
                        EtaPreferenceDivider()
                        EtaArrowPreference(title, summary = summary, enabled = custom, onClick = { showPicker = true })
                    }
                }
            }
            FeatureModelPicker(showPicker, title, models, selected, follow, { showPicker = false }, select)
        }
    }
}

/** COUI 模型选择使用内嵌列表页，搜索和选中行均沿用 COUI 控件。 */
@Composable
private fun CouiCompactionModelPicker(
    models: List<FeatureModelOption>,
    selected: String,
    providerId: String,
    onBack: () -> Unit,
    onSelect: (FeatureModelOption?) -> Unit,
) {
    var search by remember { mutableStateOf("") }
    BackHandler(onBack = onBack)
    val filtered = models.filter {
        search.isBlank() || "${it.provider} ${it.name} ${it.apiName}".contains(search.trim(), true)
    }
    CouiLazyScaffoldPage(title = stringResource(R.string.compaction_model_title), onBack = onBack) {
        item {
            CouiTextField(search, { search = it }, modifier = Modifier.padding(16.dp),
                placeholder = stringResource(R.string.compaction_model_search))
        }
        item {
            CouiPreferenceGroup {
                CouiSelectPreference(title = stringResource(R.string.compaction_model_follow_current),
                    selected = selected.isBlank(), onSelect = { onSelect(null) })
            }
        }
        if (filtered.isNotEmpty()) {
            // 一张卡片内按 COUI 惯例用分隔线排开，避免每个模型各自一张卡片。
            item {
                CouiPreferenceGroup {
                    filtered.forEachIndexed { index, model ->
                        if (index > 0) CouiIconDivider()
                        CouiSelectPreference(title = model.name, summary = "${model.provider} · ${model.apiName}",
                            selected = selected == model.id && providerId == model.providerId, onSelect = { onSelect(model) })
                    }
                }
            }
        } else {
            item {
                CouiPreferenceGroup { CouiPreference(title = stringResource(R.string.compaction_model_empty)) }
            }
        }
    }
}
