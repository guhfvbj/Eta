package io.github.mangi.eta.agent.browser

import android.app.Application
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BrowserInteractionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun clickAndTypeWaitForScrollLayoutAndRetryOnlyPreparation() {
        for (action in listOf("click", "type")) {
            val session = AgentBrowserSession()
            val view = RecordingWebView(session, initiallyOccluded = true)
            seed(session, view)
            try {
                val result = execute(session, JSONObject().put("action", action).put("selector", "#target").put("text", "value"))
                assertTrue(result.toString(), result.getBoolean("ok"))
                assertEquals(2, view.preparations)
                assertEquals(1, view.actions)
                assertTrue(view.scripts.take(2).all { it.contains("scrollIntoView") })
            } finally { session.releaseIdlePage(dispose = true) }
        }
    }

    @Test fun disabledTargetFailsWithoutAnyClickOrFocus() {
        val session = AgentBrowserSession()
        val view = RecordingWebView(session, disabled = true)
        seed(session, view)
        try {
            val result = execute(session, JSONObject().put("action", "click").put("selector", "#target"))
            assertEquals("SCRIPT_FAILED", result.getString("code"))
            assertEquals(1, view.preparations)
            assertEquals(0, view.actions)
        } finally { session.releaseIdlePage(dispose = true) }
    }

    @Test fun cancellationDuringLayoutWaitPreventsTheSideEffect() {
        val session = AgentBrowserSession()
        val view = RecordingWebView(session, cancelPreparation = true)
        seed(session, view)
        try {
            val result = execute(session, JSONObject().put("action", "click").put("selector", "#target"))
            assertEquals("CANCELLED", result.getString("code"))
            assertEquals(0, view.actions)
        } finally { session.releaseIdlePage(dispose = true) }
    }

    @Test fun postActionTimeoutWarnsAboutCompletedSideEffectsAndDoesNotReplayThem() {
        val session = AgentBrowserSession()
        val view = RecordingWebView(session, startsNavigation = true)
        seed(session, view)
        try {
            val result = execute(session, JSONObject().put("action", "click").put("selector", "#target").put("timeout_ms", 500))
            assertEquals("ACTION_TIMEOUT", result.getString("code"))
            assertTrue(result.getString("message").contains("操作已执行"))
            assertFalse(result.getBoolean("ok"))
            assertEquals(1, view.actions)
        } finally { session.releaseIdlePage(dispose = true) }
    }

    @Test fun postActionTimeoutDefaultsAndBoundsAreExplicit() {
        val session = AgentBrowserSession()
        val method = AgentBrowserSession::class.java.getDeclaredMethod("postActionTimeout", JSONObject::class.java)
            .apply { isAccessible = true }
        assertEquals(25_000L, method.invoke(session, JSONObject()))
        assertEquals(60_000L, method.invoke(session, JSONObject().put("timeout_ms", 100_000)))
        assertEquals(500L, method.invoke(session, JSONObject().put("timeout_ms", 0)))
    }

    private fun seed(session: AgentBrowserSession, view: WebView) {
        session.initialize(context)
        setField(session, "webView", view)
        setField(session, "currentUrl", "https://example.test")
        publish(session)
    }

    private fun execute(session: AgentBrowserSession, args: JSONObject): JSONObject {
        val task = FutureTask { session.execute(context, args, "run", "tool") }
        val worker = Thread(task).apply { start() }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            while (!task.isDone && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue("browser action did not finish", task.isDone)
            return JSONObject(task.get().content)
        } finally { worker.interrupt(); worker.join(1_000) }
    }

    private fun setField(session: AgentBrowserSession, name: String, value: Any) {
        AgentBrowserSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }

    private fun publish(session: AgentBrowserSession) {
        AgentBrowserSession::class.java.getDeclaredMethod("publishSnapshotOnMain").apply { isAccessible = true }.invoke(session)
    }

    private inner class RecordingWebView(
        val session: AgentBrowserSession,
        val initiallyOccluded: Boolean = false,
        val disabled: Boolean = false,
        val cancelPreparation: Boolean = false,
        val startsNavigation: Boolean = false,
    ) : WebView(context) {
        var preparations = 0
        var actions = 0
        val scripts = mutableListOf<String>()
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            scripts += script
            val value = if (script.contains("interaction_ready:")) {
                preparations++
                if (cancelPreparation) session.interruptAgentAction("run")
                val ready = !disabled && (!initiallyOccluded || preparations > 1)
                JSONObject().put("interaction_ready", ready).put("reason", if (disabled) "TARGET_DISABLED" else "TARGET_OCCLUDED")
            } else {
                actions++
                if (startsNavigation) {
                    setField(session, "currentLoading", true)
                    publish(session)
                }
                JSONObject()
            }
            val envelope = JSONObject().put("ok", true).put("value", value)
            resultCallback?.onReceiveValue(JSONObject.quote(envelope.toString()))
        }
    }
}
