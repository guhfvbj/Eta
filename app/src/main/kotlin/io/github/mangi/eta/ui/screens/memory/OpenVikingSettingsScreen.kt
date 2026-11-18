package io.github.mangi.eta.ui.screens.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Footer
import com.itos.coui.layout.Group
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.OpenVikingPeerScope
import io.github.mangi.eta.data.model.OpenVikingPurpose
import io.github.mangi.eta.data.model.OpenVikingQueryExpansion
import io.github.mangi.eta.data.model.OpenVikingRewriteMode
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun OpenVikingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { OpenVikingSettingsStore(context, scope) }
    DisposableEffect(store) { onDispose { store.close() } }
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> CouiScaffoldPage(
            title = stringResource(R.string.openviking_settings_title), onBack = onBack,
        ) { OpenVikingForm(store) }
        AppearanceUiStyle.MIUIX -> MiuixScaffoldPage(
            title = stringResource(R.string.openviking_settings_title), onBack = onBack,
            modifier = Modifier.imePadding(),
        ) { item(key = "openviking_form") { OpenVikingForm(store) } }
    }
}

@Composable
private fun OpenVikingForm(store: OpenVikingSettingsStore) {
    val state = store.state
    val s = state.settings
    var advanced by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OVSection(stringResource(R.string.openviking_settings_title)) {
            OVSwitch(stringResource(R.string.openviking_enabled), s.enabled, state.loaded && !state.busy,
                stringResource(R.string.openviking_enabled_summary), store::setEnabled)
        }
        if (!state.loaded) OVNote(stringResource(R.string.openviking_loading))
        AnimatedVisibility(visible = state.loaded && s.enabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OVNote(stringResource(R.string.openviking_capability_notice))
                if (!state.parentMemoryEnabled) OVNote(stringResource(R.string.openviking_status_parent_disabled))
                if (state.credentialUnavailable) OVNote(stringResource(R.string.openviking_credentials_unavailable))
                OVSection(stringResource(R.string.openviking_basic)) {
                    OVFields {
                        OVField(stringResource(R.string.openviking_base_url), s.baseUrl) { if (!state.busy) store.edit(s.copy(baseUrl = it)) }
                        OVField(stringResource(R.string.openviking_api_key), state.apiKey, secret = true) { if (!state.busy) store.editKey(it) }
                        OVField(stringResource(R.string.openviking_actor_peer), s.actorPeer) { if (!state.busy) store.edit(s.copy(actorPeer = it)) }
                    }
                }
                OVNote(stringResource(R.string.openviking_identity_notice))
                OVSection(stringResource(R.string.openviking_features)) {
                    OVSwitch(stringResource(R.string.openviking_auto_recall), s.autoRecall, !state.busy) { store.edit(s.copy(autoRecall = it)) }
                    OVSwitch(stringResource(R.string.openviking_profile_injection), s.profileInjection, !state.busy) { store.edit(s.copy(profileInjection = it)) }
                    OVSwitch(stringResource(R.string.openviking_auto_capture), s.autoCapture, !state.busy,
                        stringResource(R.string.openviking_capture_notice)) { store.edit(s.copy(autoCapture = it)) }
                    OVSwitch(stringResource(R.string.openviking_tools_enabled), s.toolsEnabled, !state.busy) { store.edit(s.copy(toolsEnabled = it)) }
                    OVSwitch(stringResource(R.string.openviking_allow_store), s.allowExplicitStore, !state.busy) { store.edit(s.copy(allowExplicitStore = it)) }
                    OVSwitch(stringResource(R.string.openviking_allow_forget), s.allowForget, !state.busy) { store.edit(s.copy(allowForget = it)) }
                }
                OVSection(stringResource(R.string.openviking_connection_sync)) {
                    val summary = openVikingSummaryText(LocalContext.current, s, state.status,
                        state.apiKey.isNotBlank() && !state.credentialUnavailable, state.parentMemoryEnabled)
                    OVNote(summary)
                    OVNote(stringResource(R.string.openviking_sync_counts, state.status.queued, state.status.uncertain, state.status.evictedCount))
                    if (state.status.lastSuccessAt > 0) OVNote(stringResource(R.string.openviking_last_sync,
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(state.status.lastSuccessAt))))
                    if (state.status.uncertain > 0) OVNote(stringResource(R.string.openviking_uncertain_help))
                    OVFields {
                        OVButton(stringResource(R.string.openviking_test_connection), !state.busy, store::testConnection)
                        OVButton(stringResource(R.string.openviking_sync_now), !state.busy, store::syncNow)
                        OVButton(stringResource(R.string.openviking_refresh_status), !state.busy, store::refreshStatus)
                        OVButton(stringResource(R.string.openviking_clear_key), !state.busy, { confirm = "key" })
                        OVButton(stringResource(R.string.openviking_clear_local), !state.busy, { confirm = "local" })
                    }
                }
                OVSection(stringResource(R.string.openviking_advanced)) {
                    val toggleTitle = stringResource(if (advanced) R.string.openviking_collapse else R.string.openviking_expand)
                    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                        CouiPreference(title = toggleTitle, onClick = { advanced = !advanced })
                    } else BasicComponent(title = toggleTitle, onClick = { advanced = !advanced })
                    AnimatedVisibility(visible = advanced) {
                        Column {
                            OVChoice(stringResource(R.string.openviking_peer_scope),
                                listOf(stringResource(R.string.openviking_scope_actor), stringResource(R.string.openviking_scope_all)), s.peerScope.ordinal) {
                                if (!state.busy) store.edit(s.copy(peerScope = OpenVikingPeerScope.entries[it]))
                            }
                            OVChoice(stringResource(R.string.openviking_query_expansion),
                                listOf(stringResource(R.string.openviking_off), stringResource(R.string.openviking_auto)), s.queryExpansion.ordinal) {
                                if (!state.busy) store.edit(s.copy(queryExpansion = OpenVikingQueryExpansion.entries[it]))
                            }
                            OVChoice(stringResource(R.string.openviking_rewrite),
                                listOf(stringResource(R.string.openviking_off), stringResource(R.string.openviking_auto), stringResource(R.string.openviking_on)), s.rewrite.ordinal) {
                                if (!state.busy) store.edit(s.copy(rewrite = OpenVikingRewriteMode.entries[it]))
                            }
                            OVChoice(stringResource(R.string.openviking_purpose),
                                listOf(stringResource(R.string.openviking_purpose_chat), stringResource(R.string.openviking_purpose_coding)), s.purpose.ordinal) {
                                if (!state.busy) store.edit(s.copy(purpose = OpenVikingPurpose.entries[it]))
                            }
                            OVSwitch(stringResource(R.string.openviking_capture_assistant), s.captureAssistantReplies, !state.busy,
                                stringResource(R.string.openviking_capture_assistant_summary)) { store.edit(s.copy(captureAssistantReplies = it)) }
                            OVFields {
                                for ((field, resource) in openVikingNumericFields) {
                                    OVField(stringResource(resource), state.numericDrafts[field].orEmpty()) { if (!state.busy) store.editNumber(field, it) }
                                }
                            }
                        }
                    }
                }
            }
        }
        OVFields {
            OVButton(stringResource(if (state.busy) R.string.openviking_working else R.string.openviking_save),
                state.loaded && !state.busy, store::save, primary = true)
            state.message?.let { OVNote(it) }
        }
    }
    confirm?.let { action ->
        val title = stringResource(if (action == "key") R.string.openviking_clear_key else R.string.openviking_clear_local)
        val message = stringResource(if (action == "key") R.string.openviking_clear_key_confirm else R.string.openviking_clear_local_confirm)
        val onConfirm: () -> Unit = { confirm = null; if (action == "key") store.clearKey() else store.clearLocalCache() }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiConfirmDialog(visible = true, title = title, message = message, confirmText = title,
                confirmEnabled = !state.busy, destructive = true, onConfirm = onConfirm, onDismissRequest = { confirm = null })
        } else WindowDialog(show = true, title = title, summary = message, onDismissRequest = { confirm = null }) {
            MiuixDialogActions(confirmText = title, destructive = true, confirmEnabled = !state.busy,
                onCancel = { confirm = null }, onConfirm = onConfirm)
        }
    }
}

internal val openVikingNumericFields = listOf(
    "foregroundDeadlineMs" to R.string.openviking_deadline,
    "recallMaxTokens" to R.string.openviking_recall_tokens,
    "profileMaxTokens" to R.string.openviking_profile_tokens,
    "scoreThreshold" to R.string.openviking_score,
    "dedupTurns" to R.string.openviking_dedup,
    "commitTokenThreshold" to R.string.openviking_commit_tokens,
    "commitTurnThreshold" to R.string.openviking_commit_turns,
    "minCommitIntervalSeconds" to R.string.openviking_commit_interval,
    "idleCommitMinutes" to R.string.openviking_idle,
)

@Composable
private fun OVSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) Group(title = title, content = content)
    else Column {
        SmallTitle(title)
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) { content() }
    }
}

@Composable
private fun OVSwitch(title: String, checked: Boolean, enabled: Boolean, summary: String? = null, onChange: (Boolean) -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSwitchPreference(title = title, checked = checked, enabled = enabled, summary = summary, onCheckedChange = onChange)
    } else SwitchPreference(title = title, checked = checked, enabled = enabled, summary = summary, onCheckedChange = onChange)
}

@Composable
private fun OVChoice(title: String, items: List<String>, selected: Int, onChange: (Int) -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiDropdownPreference(title = title, items = items, selectedIndex = selected, onSelectedIndexChange = onChange)
    } else OverlayDropdownPreference(title = title, items = items, selectedIndex = selected, onSelectedIndexChange = onChange)
}

@Composable
private fun OVFields(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun OVField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    val transformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None
    val keyboard = if (secret) KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password) else KeyboardOptions.Default
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiTextField(value = value, onValueChange = onChange, label = label, visualTransformation = transformation, keyboardOptions = keyboard)
    } else TextField(value = value, onValueChange = onChange, label = label, singleLine = true,
        visualTransformation = transformation, keyboardOptions = keyboard, modifier = Modifier.fillMaxWidth())
    if (secret) OVButton(stringResource(if (visible) R.string.openviking_hide_key else R.string.openviking_show_key), true, { visible = !visible })
}

@Composable
private fun OVButton(text: String, enabled: Boolean, onClick: () -> Unit, primary: Boolean = false) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiButton(text = text, enabled = enabled,
        role = if (primary) CouiButtonRole.Primary else CouiButtonRole.Secondary, modifier = Modifier.fillMaxWidth(), onClick = onClick)
    else if (primary) TextButton(text = text, enabled = enabled, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.textButtonColorsPrimary(), onClick = onClick)
    else TextButton(text = text, enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = onClick)
}

@Composable
private fun OVNote(text: String) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) Footer(text = text)
    else Text(text = text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
}
