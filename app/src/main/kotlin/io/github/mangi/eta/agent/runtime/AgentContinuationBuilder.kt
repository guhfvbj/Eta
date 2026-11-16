package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.UUID

/** 用完整增量 transcript 构造已完成 run 的后续用户回合。 */
internal object AgentContinuationBuilder {
    fun build(
        request: AgentRuntimeWire.RunRequest,
        response: AgentModelClient.ModelResponse.Text,
        supplement: String,
        newRunId: String = "run-${UUID.randomUUID()}",
        createdAt: Long = System.currentTimeMillis(),
    ): AgentRuntimeWire.RunRequest {
        require(request.operation == AgentRuntimeWire.OP_CHAT) { "该运行不是可继续的对话回合" }
        val uiPayload = request.handoff?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload) }
        val baseHistory = response.contextSnapshot?.messages ?: (request.history +
            AgentModelClient.buildUserHistoryMessage(request.prompt, request.images).copy(
                messageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
            ) +
            response.transcript)
        val handoff = request.handoff?.let { original ->
            if (original.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                return@let original.copy(id = newRunId)
            }
            val payload = AgentUiHandoffPayload.from(original.payload)
            val nextSupplement = AgentUiHandoffPayload.Supplement(
                index = (payload.supplements.maxOfOrNull { it.index } ?: 0) + 1,
                text = supplement,
                createdAt = createdAt,
            )
            original.copy(
                id = newRunId,
                payload = AgentUiHandoffPayload(
                    conversationId = payload.conversationId,
                    promptSupplement = nextSupplement,
                ).toJson(),
            )
        }
        return request.copy(
            runId = newRunId,
            operation = AgentRuntimeWire.OP_CHAT,
            rewriteTargetMessageId = null,
            modelSessionId = request.effectiveModelSessionId,
            prompt = supplement,
            images = emptyList(),
            history = baseHistory,
            handoff = handoff,
        )
    }

    /** 后台完成作为系统来源的 supplement 续跑，保留既有用户与历史消息编号。 */
    fun buildJobNotice(
        base: AgentContinuationBaseStore.Base,
        notice: String,
        jobId: String,
        newRunId: String = "run-${UUID.randomUUID()}",
        createdAt: Long = System.currentTimeMillis(),
    ): AgentRuntimeWire.RunRequest {
        val request = base.request
        val previous = request.handoff?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload) }
        val history = base.snapshot?.messages ?: (request.history +
            AgentModelClient.buildUserHistoryMessage(request.prompt, request.images).copy(
                messageId = previous?.promptMessageId(base.runId) ?: "user-${base.runId}",
            ) + base.transcript)
        val payload = AgentUiHandoffPayload(
            conversationId = base.conversationId,
            promptSupplement = AgentUiHandoffPayload.Supplement(
                index = (previous?.lastSupplementIndex ?: 0) + 1, text = notice, createdAt = createdAt,
                source = AgentRuntimeWire.SUPPLEMENT_SOURCE_BACKGROUND_JOB, jobId = jobId,
            ),
            origin = AgentUiHandoffPayload.ORIGIN_JOB_WAKEUP,
        )
        return request.copy(
            runId = newRunId, operation = AgentRuntimeWire.OP_CHAT, rewriteTargetMessageId = null,
            modelSessionId = request.effectiveModelSessionId, prompt = notice, images = emptyList(), history = history,
            handoff = AgentRuntimeWire.EntryHandoff(newRunId, AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                payload.toJson(), dismissEntrySurfaceOnForegroundOperation = false),
        )
    }

}
