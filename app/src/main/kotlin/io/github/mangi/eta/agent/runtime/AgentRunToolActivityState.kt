package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot

/** Compact terminal projection shared by checkpoint and archive persistence. */
internal class AgentRunToolActivityState {
    private val running = linkedMapOf<Pair<Int, String>, AgentEvent.ToolStarted>()
    private val children = mutableMapOf<Pair<Int, String>, AgentEvent.SubagentProgress>()
    private val hosted = linkedMapOf<Pair<Int, String>, AgentEvent.HostedToolStarted>()

    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.ToolStarted -> running[event.round to event.toolCallId] = event
            is AgentEvent.ToolFinished -> {
                running.remove(event.round to event.toolCallId)
                children.remove(event.round to event.toolCallId)
            }
            is AgentEvent.SubagentProgress -> children[event.round to event.toolCallId] = event
            is AgentEvent.HostedToolStarted -> hosted[event.round to event.toolCallId] = event
            is AgentEvent.HostedToolFinished -> hosted.remove(event.round to event.toolCallId)
            else -> Unit
        }
    }

    fun finish(reason: String, cancelled: Boolean): List<AgentEvent> = buildList {
        running.forEach { (key, tool) ->
            val progress = children[key]
            val command = progress?.snapshotJson ?: tool.command
            val snapshot = if (tool.name == "subagent") SubagentActivitySnapshot.decode(command)
                ?.terminated(reason, cancelled)?.encode() else null
            // Persist the terminal child state through ToolFinished; SubagentProgress stays transient.
            add(AgentEvent.ToolFinished(tool.round, tool.toolCallId, tool.name, reason, 0, 0,
                success = false, command = snapshot ?: command))
        }
        hosted.values.forEach { add(AgentEvent.HostedToolFinished(it.round, it.toolCallId, it.name, false)) }
        running.clear()
        children.clear()
        hosted.clear()
    }
}
