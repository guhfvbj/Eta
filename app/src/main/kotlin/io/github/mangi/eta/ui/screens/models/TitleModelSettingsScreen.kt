package io.github.mangi.eta.ui.screens.models

import io.github.mangi.eta.ui.components.EtaScaffoldPage as MiuixScaffoldPage
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.*

@Composable internal fun TitleModelSettingsScreen(onBack: () -> Unit) {
    val state = rememberFeatureSettings()
    val settings = state.value
    val models = rememberFeatureModels()
    val enabled = settings.optBoolean("title_enabled", true)
    val custom = settings.optBoolean("title_custom")
    MiuixScaffoldPage(title = stringResource(R.string.title_model_title), onBack = onBack) {
        item { EtaPreferenceGroup { EtaActionPreference("自动会话标题", summary = "为新会话自动生成简短标题。未开启自定义时使用当前会话模型；失败时保留本地标题，不覆盖手动修改的标题。") } }
        item {
            EtaPreferenceGroup {
                EtaSwitchPreference("自动生成会话标题",enabled,{ state.set("title_enabled",it) })
                EtaPreferenceDivider()
                EtaSwitchPreference(stringResource(R.string.model_feature_custom),custom,{ state.set("title_custom",it) },enabled = enabled,
                    summary = "关闭后跟随当前对话模型，保留上次自定义选择")
                if(custom) {
                    EtaPreferenceDivider()
                    FeatureModelRow(stringResource(R.string.model_feature_select),models,settings.optString("title_custom_model_id"),enabled) {
                        state.set("title_custom_model_id",it?.id.orEmpty()); state.set("title_custom_provider_id",it?.providerId.orEmpty())
                    }
                }
                EtaPreferenceDivider()
                EtaSwitchPreference(stringResource(R.string.model_feature_title_follow),settings.optBoolean("title_follow"),{ state.set("title_follow",it) },
                    enabled = enabled, summary = stringResource(R.string.model_feature_title_follow_summary))
            }
        }
    }
}
