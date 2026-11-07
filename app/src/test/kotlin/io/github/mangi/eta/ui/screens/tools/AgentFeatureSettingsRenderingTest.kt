package io.github.mangi.eta.ui.screens.tools

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(EtaRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class AgentFeatureSettingsRenderingTest {
    @Test fun loadingControlsRenderUsingTheExistingMiuixPage() = withContent({ AgentFeatureSettingsScreen {} }) { view ->
        val ownerView = view.getChildAt(0)
        val owner = ownerView.javaClass.getMethod("getSemanticsOwner").invoke(ownerView) as SemanticsOwner
        val nodes = descendants(owner.unmergedRootSemanticsNode)
        for (text in listOf("模型与工具策略", "工具按需加载", "加载方式")) {
            assertTrue("未渲染：$text", nodes.any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true })
        }
    }
    private fun descendants(node: SemanticsNode): List<SemanticsNode> =
        listOf(node) + node.children.flatMap(::descendants)

    private fun withContent(content: @Composable () -> Unit, verify: (ComposeView) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val view = ComposeView(controller.get())
        try {
            controller.get().setContentView(view)
            view.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    top.yukonga.miuix.kmp.theme.MiuixTheme { content() }
                }
            }
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            view.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
            view.layout(0, 0, 320, view.measuredHeight)
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            verify(view)
        } finally {
            view.disposeComposition()
            controller.pause().stop().destroy()
        }
    }
}
