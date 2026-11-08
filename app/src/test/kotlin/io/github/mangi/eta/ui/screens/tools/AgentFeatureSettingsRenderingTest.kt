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
    @Test fun loadingControlsRenderUsingTheExistingMiuixPage() = withContent({ io.github.mangi.eta.ui.screens.models.DecisionModelSettingsScreen {} }) { view ->
        val ownerView = view.getChildAt(0)
        val owner = ownerView.javaClass.getMethod("getSemanticsOwner").invoke(ownerView) as SemanticsOwner
        val nodes = descendants(owner.unmergedRootSemanticsNode)
        for (text in listOf("判断模型", "总开关", "启用判断模型", "运行模式", "模型与端点", "连通性", "高级", "诊断与遥测", "清理")) {
            assertTrue("未渲染：$text", nodes.any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true })
        }
    }
    @Test fun titleSettingsRenderIndependentCustomAndFollowOptions() = withContent({ io.github.mangi.eta.ui.screens.models.TitleModelSettingsScreen {} }) { view ->
        assertRendered(view,listOf("标题模型","自动生成会话标题","使用自定义模型","每轮回复后刷新标题"))
    }
    @Test fun recipesRenderPermissionsAndAuditableEmptyState() = withContent({ ToolRecipesScreen {} }) { view ->
        assertRendered(view,listOf("模型自建工具","配方步骤权限"))
    }
    private fun assertRendered(view: ComposeView,texts: List<String>) {
        val ownerView=view.getChildAt(0)
        val owner=ownerView.javaClass.getMethod("getSemanticsOwner").invoke(ownerView) as SemanticsOwner
        val nodes=descendants(owner.unmergedRootSemanticsNode)
        texts.forEach { text -> assertTrue("未渲染：$text",nodes.any { it.config.getOrNull(SemanticsProperties.Text)?.any { entry->entry.text==text }==true }) }
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
            view.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 390, view.measuredHeight)
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
            verify(view)
        } finally {
            view.disposeComposition()
            controller.pause().stop().destroy()
        }
    }
}
