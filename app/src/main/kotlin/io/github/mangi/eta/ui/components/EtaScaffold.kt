package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior

@Composable
internal fun EtaScaffoldPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    actions: @Composable RowScope.() -> Unit = {},
    overlay: @Composable () -> Unit = {},
    compactTitle: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    EtaPreferenceTheme {
        MiuixScaffoldPage(title = title, onBack = onBack, modifier = modifier, listState = listState, actions = actions, overlay = overlay, compactTitle = compactTitle, content = content)
    }
}

@Composable
internal fun EtaScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues, ScrollBehavior, Dp) -> Unit,
) {
    EtaPreferenceTheme {
        MiuixScaffold(title = title, onBack = onBack, modifier = modifier, actions = actions, content = content)
    }
}
