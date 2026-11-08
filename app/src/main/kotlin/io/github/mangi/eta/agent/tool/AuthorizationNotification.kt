package io.github.mangi.eta.agent.tool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.core.EtaTrace
import io.github.mangi.eta.ui.MainActivity

/** Background/overlay runs expose the same pending request through a notification, never auto-approve. */
internal object AuthorizationNotification {
    private const val CHANNEL = "eta_tool_authorization"
    fun show(request: AgentToolAuthorization.Request): Boolean = runCatching {
        val context = AgentAppContext.resolve() ?: return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || !manager.areNotificationsEnabled()) return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "工具操作授权", NotificationManager.IMPORTANCE_HIGH))
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, request.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(request.id.hashCode(), Notification.Builder(context, CHANNEL).setSmallIcon(context.applicationInfo.icon)
            .setContentTitle("Eta 需要确认工具操作").setContentText("${request.tool} · ${request.risk.level}，点击查看并授权")
            .setContentIntent(pending).setAutoCancel(true).setTimeoutAfter(60000).build())
        EtaTrace.log("tool_authorization", "pending tool=${request.tool} notification=${manager.areNotificationsEnabled()}")
        true
    }.getOrDefault(false)
    fun cancel(request: AgentToolAuthorization.Request) { runCatching {
        AgentAppContext.resolve()?.getSystemService(NotificationManager::class.java)?.cancel(request.id.hashCode())
    } }
}
