package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConversationRevisionReducerTest {
    @Test fun backgroundNoticeStillCountsForOneToOneUserBoundaries() {
        val users = listOf(UserMessageUi("user-first", "原任务"),
            UserMessageUi("user-wake-supplement-1", "后台完成",
                origin = io.github.mangi.eta.ui.model.UserMessageOrigin.BackgroundJob),
            UserMessageUi("user-next", "后续问题"))
        val history = users.map { AgentModelClient.ConversationMessage("user", it.content, messageId = it.id) }
        val state = AgentChatUiState(messages = users, history = history,
            input = "", isStreaming = false, thinkingEnabled = false)
        val boundary = AgentConversationRevisionReducer.boundary(state, "user-next")!!
        assertEquals(2, boundary.userMessageIndex)
        assertEquals(history.take(2), boundary.historyPrefix)
        assertFalse(boundary.contextWasCompacted)
        val noticeBoundary = AgentConversationRevisionReducer.boundary(state, "user-wake-supplement-1")!!
        assertEquals(history.take(1), noticeBoundary.historyPrefix)
        assertEquals(1, noticeBoundary.laterTurnCount)
    }


    @Test
    fun editingUserTurnCoveredBySummaryDropsStaleSummary() {
        val summary = AgentModelClient.ConversationMessage("assistant", "旧操作结果", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 2)
        val state = AgentChatUiState(
            messages = listOf(UserMessageUi("old", "旧请求"), UserMessageUi("latest", "最新请求")),
            history = listOf(summary, AgentModelClient.ConversationMessage("user", "最新请求")),
            input = "", isStreaming = false, thinkingEnabled = false,
        )
        val boundary = AgentConversationRevisionReducer.boundary(state, "latest")!!
        assertTrue(boundary.contextWasCompacted)
        assertTrue(boundary.historyPrefix.isEmpty())
        val next = state.copy(messages = state.messages + UserMessageUi("next", "后续请求"),
            history = state.history + AgentModelClient.ConversationMessage("user", "后续请求"))
        assertTrue(AgentConversationRevisionReducer.boundary(next, "next")!!.historyPrefix.contains(summary))
    }

    @Test
    fun boundaryMapsAssistantToItsUserTurnAndKeepsToolTranscriptPrefix() {
        val state = conversationState()

        val boundary = AgentConversationRevisionReducer.boundary(state, "assistant-2")!!

        assertEquals("user-2", boundary.userMessage.id)
        assertEquals(4, boundary.userMessageIndex)
        assertEquals(1, boundary.laterTurnCount)
        assertFalse(boundary.contextWasCompacted)
        assertEquals(
            listOf("user", "assistant", "tool", "assistant"),
            boundary.historyPrefix.map { it.role },
        )
    }

    @Test
    fun deleteFromMiddleTurnTruncatesMessagesAndHistoryTogether() {
        val state = conversationState().copy(appliedRuntimeRunIds = listOf("run-1", "run-2"))

        val revised = AgentConversationRevisionReducer.deleteFromTurn(state, "assistant-2")!!

        assertEquals(
            listOf("user-1", "thinking-1", "tool-1", "assistant-1"),
            revised.messages.map { it.id },
        )
        assertEquals(listOf("user", "assistant", "tool", "assistant"), revised.history.map { it.role })
        assertEquals(listOf("run-1", "run-2"), revised.appliedRuntimeRunIds)
    }

    @Test
    fun compactedCheckpointAlignsRetainedTurnsFromTheTail() {
        val full = conversationState()
        val compacted = full.copy(
            history = listOf(
                AgentModelClient.ConversationMessage(role = "system", content = "已压缩"),
                AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
                AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
                AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
            )
        )

        val missing = AgentConversationRevisionReducer.boundary(compacted, "user-1")!!
        val retained = AgentConversationRevisionReducer.boundary(compacted, "user-2")!!

        assertTrue(missing.contextWasCompacted)
        assertTrue(missing.historyPrefix.isEmpty())
        assertFalse(retained.contextWasCompacted)
        assertEquals(listOf("system"), retained.historyPrefix.map { it.role })
    }

    @Test
    fun editAndDeleteAfterSummaryKeepCompactedHistoryAndTrimJournalSeparately() {
        val full = conversationState()
        val summary = AgentModelClient.ConversationMessage("assistant", "第一轮摘要", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 1)
        val state = full.copy(journal = full.history, history = listOf(summary) + full.history.drop(4))
        val boundary = AgentConversationRevisionReducer.boundary(state, "assistant-3")!!
        assertEquals(state.history.take(3), boundary.historyPrefix)
        assertEquals(full.history.take(6), boundary.journalPrefix)
        assertFalse(boundary.contextWasCompacted)
        assertFalse(boundary.historyPrefix.any { it.content == "第一答" || it.role == "tool" })
        val deleted = AgentConversationRevisionReducer.deleteFromTurn(state, "user-3")!!
        assertEquals(boundary.historyPrefix, deleted.history)
        assertEquals(boundary.journalPrefix, deleted.journal)

        // Tail alignment also works when the UI has paged out the covered first turn.
        assertEquals(boundary.historyPrefix, AgentConversationRevisionReducer.boundary(
            state.copy(messages = state.messages.drop(4)), "user-3")!!.historyPrefix)
    }

    @Test
    fun revisionInsideSummaryCoverageFallsBackOnlyToAvailableJournalPrefix() {
        val full = conversationState()
        val summary = AgentModelClient.ConversationMessage("assistant", "含第二轮旧结果", contextSummary = true,
            compactedUserTurns = 1, summaryThroughUserTurn = 2)
        val state = full.copy(journal = full.history, history = listOf(summary) + full.history.drop(4))
        val covered = AgentConversationRevisionReducer.boundary(state, "user-2")!!
        assertTrue(covered.contextWasCompacted)
        assertEquals(full.history.take(4), covered.historyPrefix)
        assertEquals(covered.historyPrefix, covered.journalPrefix)
        assertTrue(AgentConversationRevisionReducer.boundary(state, "user-1")!!.historyPrefix.isEmpty())
        assertEquals(state.history.take(3), AgentConversationRevisionReducer.boundary(state, "user-3")!!.historyPrefix)
        val legacy = state.copy(journal = state.history)
        assertTrue(AgentConversationRevisionReducer.boundary(legacy, "user-2")!!.journalPrefix.isEmpty())
    }

    @Test
    fun visibleMessagesStopAtEditedUserWithoutMutatingTheSource() {
        val messages = conversationState().messages

        val visible = AgentConversationRevisionReducer.visibleMessagesForEdit(messages, "user-2")

        assertEquals(listOf("user-1", "thinking-1", "tool-1", "assistant-1", "user-2"), visible.map { it.id })
        assertEquals(8, messages.size)
    }

    @Test
    fun invalidMessageDoesNotChangeConversation() {
        val state = conversationState()

        assertNull(AgentConversationRevisionReducer.boundary(state, "missing"))
        assertNull(AgentConversationRevisionReducer.deleteFromTurn(state, "missing"))
        assertEquals(
            state.messages,
            AgentConversationRevisionReducer.visibleMessagesForEdit(state.messages, "missing"),
        )
    }

    private fun conversationState(): AgentChatUiState = AgentChatUiState(
        messages = listOf(
            UserMessageUi(id = "user-1", content = "第一问"),
            ThinkingMessageUi(id = "thinking-1", content = "思考", isStreaming = false),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "test",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{}",
            ),
            AgentMessageUi(id = "assistant-1", content = "第一答"),
            UserMessageUi(id = "user-2", content = "第二问", images = listOf("data:image/png;base64,AA==")),
            AgentMessageUi(id = "assistant-2", content = "第二答"),
            UserMessageUi(id = "user-3", content = "第三问"),
            AgentMessageUi(id = "assistant-3", content = "第三答"),
        ),
        history = listOf(
            AgentModelClient.ConversationMessage(role = "user", content = "第一问"),
            AgentModelClient.ConversationMessage(role = "assistant", toolCallsJson = "[]"),
            AgentModelClient.ConversationMessage(role = "tool", content = "结果"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第一答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第二问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第二答"),
            AgentModelClient.ConversationMessage(role = "user", content = "第三问"),
            AgentModelClient.ConversationMessage(role = "assistant", content = "第三答"),
        ),
        input = "草稿",
        isStreaming = false,
        thinkingEnabled = false,
    )
}
