package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.UserMessageUi

/** 以用户轮次为边界同步裁剪展示消息与模型上下文。 */
internal object AgentConversationRevisionReducer {
    data class Boundary(
        val userMessage: UserMessageUi,
        val userMessageIndex: Int,
        val historyPrefix: List<AgentModelClient.ConversationMessage>,
        val journalPrefix: List<AgentModelClient.ConversationMessage> = historyPrefix,
        val laterTurnCount: Int,
        val contextWasCompacted: Boolean,
    )

    fun boundary(state: AgentChatUiState, targetMessageId: String): Boundary? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val userMessage = state.messages[userMessageIndex] as UserMessageUi
        val userMessageIndices = state.messages.indices.filter { state.messages[it] is UserMessageUi }
        val targetUserOrdinal = userMessageIndices.indexOf(userMessageIndex)
        if (targetUserOrdinal < 0) return null

        // 旧版本可能已经丢失前缀；只能对已有记录做尾部对齐，不能伪造恢复。
        fun prefix(source: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage>? {
            val users = source.indices.filter { source[it].role == "user" }
            val ordinal = users.size - (userMessageIndices.size - targetUserOrdinal)
            return users.getOrNull(ordinal)?.let(source::take)
        }
        fun validPrefix(source: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> {
            val turn = source.sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 } + 1
            return source.filterNot { it.contextSummary && it.summaryThroughUserTurn >= turn }
        }
        val historyPrefix = prefix(state.history)
        val journalPrefix = if (state.journal.isNotEmpty()) validPrefix(prefix(state.journal).orEmpty()) else null
        val targetTurn = historyPrefix.orEmpty().sumOf { it.compactedUserTurns + if (it.role == "user") 1 else 0 } + 1
        val invalidatesSummary = state.history.any {
            it.contextSummary && (historyPrefix == null || it.summaryThroughUserTurn >= targetTurn)
        }
        val retainedHistory = if (state.history.isEmpty() || historyPrefix == null || invalidatesSummary) {
            journalPrefix ?: validPrefix(historyPrefix.orEmpty())
        } else historyPrefix

        return Boundary(
            userMessage = userMessage,
            userMessageIndex = userMessageIndex,
            historyPrefix = retainedHistory,
            journalPrefix = journalPrefix ?: retainedHistory,
            laterTurnCount = userMessageIndices.size - targetUserOrdinal - 1,
            contextWasCompacted = historyPrefix == null || invalidatesSummary,
        )
    }

    fun deleteFromTurn(state: AgentChatUiState, targetMessageId: String): AgentChatUiState? {
        val boundary = boundary(state, targetMessageId) ?: return null
        return state.copy(
            messages = state.messages.take(boundary.userMessageIndex),
            history = boundary.historyPrefix,
            journal = boundary.journalPrefix,
            messageEdit = null,
        )
    }

    fun visibleMessagesForEdit(
        messages: List<AgentChatMessageUi>,
        targetMessageId: String?,
    ): List<AgentChatMessageUi> {
        if (targetMessageId == null) return messages
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        return if (targetIndex < 0) messages else messages.take(targetIndex + 1)
    }
}
