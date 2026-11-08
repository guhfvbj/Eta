package io.github.mangi.eta.ui.components

import androidx.compose.runtime.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.mangi.eta.agent.tool.AgentToolAuthorization
import top.yukonga.miuix.kmp.basic.TextButton

@Composable internal fun AgentToolAuthorizationHost() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hostId = remember { java.util.UUID.randomUUID().toString() }
    DisposableEffect(lifecycle, hostId) {
        fun update() = AgentToolAuthorization.setHostVisible(hostId, lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); AgentToolAuthorization.setHostVisible(hostId, false) }
    }
    val requests by AgentToolAuthorization.pending.collectAsState()
    requests.firstOrNull()?.let { request ->
        EtaWindowDialog(show = true, title = "确认工具操作 · ${request.risk.level}",
            summary = "会话：${request.conversation}\n工具：${request.tool}\n${request.risk.reasons.joinToString("\n")}\n参数：${request.arguments.take(3000)}",
            onDismissRequest = { AgentToolAuthorization.answer(request.id, false) }) {
            TextButton(text = "拒绝", onClick = { AgentToolAuthorization.answer(request.id, false) })
            TextButton(text = if (request.repeatAllowed) "允许本会话中的相同操作（60 分钟）" else "允许这次操作", onClick = { AgentToolAuthorization.answer(request.id, true) })
        }
    }
}
