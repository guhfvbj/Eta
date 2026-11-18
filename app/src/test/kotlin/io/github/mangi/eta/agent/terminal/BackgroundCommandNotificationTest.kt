package io.github.mangi.eta.agent.terminal

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import io.github.mangi.eta.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BackgroundCommandNotificationTest {
    @Test fun notificationShowsOutcomeAndOpensTerminalWithoutCommandOrLogContents() {
        val context = RuntimeEnvironment.getApplication()
        val task = DetachedTask("notification-job", 42, "token", "echo secret-command", "/private", "user",
            TerminalEnvironment.ANDROID, "/private/log", 1, commandTask = true, description = "生成报告",
            completedAt = 2, exitCode = 0)
        BackgroundCommandNotification.show(context, task)
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals("后台命令已完成", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("生成报告", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertFalse(notification.extras.toString().contains("secret-command"))
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertEquals(MainActivity.ACTION_OPEN_TERMINAL, shadowOf(notification.contentIntent).savedIntent.action)
    }

    @Test fun notificationIncludesWakeFallbackNoteAndRootUnavailableTitle() {
        val context = RuntimeEnvironment.getApplication()
        val task = DetachedTask("notification-job-root", 42, "token", "echo secret", "/private", "root",
            TerminalEnvironment.ANDROID, "/private/log", 1, commandTask = true, description = "Root 任务",
            completedAt = 2, endReason = "root_unavailable")
        BackgroundCommandNotification.show(context, task, "已连续唤醒 3 次，本次仅通知")
        val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single().notification
        assertEquals("后台命令已结束（Root 不可用）", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Root 任务 · 已连续唤醒 3 次，本次仅通知", notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }
}
