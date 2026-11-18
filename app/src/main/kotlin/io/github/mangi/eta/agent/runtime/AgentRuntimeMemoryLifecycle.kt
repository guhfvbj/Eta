package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.memory.ConversationMemoryPins

/** A checkpoint is not compaction. Only a durably committed replacement emits COMPLETED. */
internal class AgentRuntimeMemoryLifecycle(private val conversationId: String?, private val onCompacted: (String) -> Unit) {
    fun accept(event: AgentEvent) {
        if (event is AgentEvent.ContextCompaction && event.phase == AgentEvent.ContextCompaction.PHASE_COMPLETED) {
            ConversationMemoryPins.forget(conversationId)
            onCompacted(event.operationId)
        }
    }
}
