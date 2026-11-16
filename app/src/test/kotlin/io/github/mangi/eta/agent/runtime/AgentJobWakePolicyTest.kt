package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.repository.ConversationRuntimeConfigResolver
import org.junit.Assert.*
import org.junit.Test

class AgentJobWakePolicyTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
        model = "model-a", systemPrompt = "", terminalTools = true,
        reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.LOW)
    private val resolved = ConversationRuntimeConfigResolver.Resolved(config, "local-a", false, true)
    private val user = ConversationMessageEntity("user-origin", "c", 0, "user", "原任务")
    private val stamp = AgentJobWakePolicy.historyStamp(user, "[\"earlier\"]")
    private val base = AgentContinuationBaseStore.Base("c", "origin",
        AgentRuntimeWire.RunRequest("origin", "原任务", config, emptyList()), null, emptyList(), 100,
        originModelId = "model-a", originModelDisplayName = "模型 A",
        historyUserMessageId = stamp.userMessageId, historyUserDigest = stamp.userDigest,
        appliedRuntimeRunIds = stamp.appliedRunIds)

    @Test fun noToolsOrRevokedTerminalPermissionDeclinesWakeForSystemNotification() {
        assertTrue(AgentJobWakePolicy.canWake(base, resolved, stamp))
        assertFalse(AgentJobWakePolicy.canWake(base, resolved.copy(supportsTools = false), stamp))
        assertFalse(AgentJobWakePolicy.canWake(base, resolved.copy(config = config.copy(terminalTools = false)), stamp))
    }

    @Test fun newUserFailedRunAndSameIdEditsInvalidateOldHistory() {
        val nextUser = AgentJobWakePolicy.historyStamp(user.copy(id = "user-failed-run", content = "新请求"), "[\"earlier\"]")
        assertFalse(AgentJobWakePolicy.canWake(base, resolved, nextUser))
        assertFalse(AgentJobWakePolicy.isFresh(base,
            AgentJobWakePolicy.historyStamp(user.copy(content = "修改原请求"), "[\"earlier\"]")))
        assertFalse(AgentJobWakePolicy.isFresh(base,
            AgentJobWakePolicy.historyStamp(user.copy(imagesJson = "[\"new-image\"]"), "[\"earlier\"]")))
        assertFalse(AgentJobWakePolicy.isFresh(base, stamp.copy(appliedRunIds = listOf("earlier", "newer-run"))))
    }

    @Test fun applyingTheBaseRunAndNonHistoryMetadataChangesDoNotInvalidateIt() {
        // 普通 run 结果应用会晚于基座写入；标题/置顶/模型选择的 updatedAt 不参与历史戳。
        assertTrue(AgentJobWakePolicy.isFresh(base, stamp.copy(appliedRunIds = listOf("earlier", "origin"))))
        assertTrue(AgentJobWakePolicy.canWake(base, resolved.copy(config = config.copy(model = "model-b")), stamp))
        assertFalse(AgentJobWakePolicy.isFresh(base.copy(historyUserDigest = ""), stamp)) // 旧基座安全降级
    }

    @Test fun modelSwitchOrFallbackAddsNonInstructionalConfigurationProvenance() {
        assertEquals("完成通知", AgentJobWakePolicy.notice(base, resolved, "完成通知"))
        val changed = AgentJobWakePolicy.notice(base, resolved.copy(config = config.copy(model = "model-b")), "完成通知")
        assertTrue(changed.startsWith("完成通知\n"))
        assertTrue(changed.contains("model-a → model-b"))
        assertTrue(changed.contains("思考深度 low"))
        assertTrue(AgentJobWakePolicy.notice(base, resolved.copy(fellBackToDefault = true), "完成通知")
            .contains("运行配置已按当前会话设置更新"))
        assertEquals("完成通知", AgentJobWakePolicy.notice(base.copy(originModelId = ""), resolved, "完成通知"))
    }
}
