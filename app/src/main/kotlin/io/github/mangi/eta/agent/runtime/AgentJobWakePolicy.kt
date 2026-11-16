package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.repository.ConversationRuntimeConfigResolver
import java.security.MessageDigest

/** 无 Android 服务依赖的续跑预检，拒绝时由宿主发送系统通知。 */
internal object AgentJobWakePolicy {
    data class HistoryStamp(val userMessageId: String, val userDigest: String, val appliedRunIds: List<String>)

    fun historyStamp(user: ConversationMessageEntity?, appliedRunIdsJson: String): HistoryStamp {
        val ids = org.json.JSONArray(appliedRunIdsJson)
        // 内容与附件一起校验，同编号编辑也能使旧基座失效；仅存摘要，不额外保存用户正文。
        val digest = user?.let { message ->
            MessageDigest.getInstance("SHA-256").digest(
                "${message.content.length}:${message.content}${message.imagesJson}".toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }
        }.orEmpty()
        return HistoryStamp(user?.id.orEmpty(), digest, (0 until ids.length()).map { ids.getString(it) })
    }

    fun isFresh(base: AgentContinuationBaseStore.Base, current: HistoryStamp): Boolean =
        base.historyUserMessageId.isNotBlank() && base.historyUserDigest.isNotBlank() &&
            base.historyUserMessageId == current.userMessageId && base.historyUserDigest == current.userDigest &&
            current.appliedRunIds.all { it == base.runId || it in base.appliedRuntimeRunIds }

    fun canWake(base: AgentContinuationBaseStore.Base, resolved: ConversationRuntimeConfigResolver.Resolved,
        current: HistoryStamp): Boolean = resolved.canWakeForTerminalJob && isFresh(base, current)

    fun notice(base: AgentContinuationBaseStore.Base, resolved: ConversationRuntimeConfigResolver.Resolved, text: String): String {
        if (base.originModelId.isBlank() ||
            (base.originModelId == resolved.config.model && !resolved.fellBackToDefault)) return text
        // 环境事实提示，不向模型注入新的执行指令。
        return "$text\n[运行配置已按当前会话设置更新：模型 ${base.originModelId} → ${resolved.config.model}，" +
            "思考深度 ${resolved.config.effectiveReasoningEffort.wireValue}]"
    }
}
