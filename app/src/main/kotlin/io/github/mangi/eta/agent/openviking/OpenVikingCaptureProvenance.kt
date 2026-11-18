package io.github.mangi.eta.agent.openviking

import io.github.mangi.eta.agent.runtime.AgentEvent

/** Conservative whole-span gate. Labels/regex cannot prove a derivative of tool output safe. */
internal class OpenVikingCaptureProvenance {
    private val userFacts = mutableListOf<String>()
    @Volatile
    var tainted = false
        private set
    @Volatile
    var explicitStoreBlocked = false
        private set
    @Volatile
    var failed = false
        private set
    fun taint() { tainted = true; explicitStoreBlocked = true }
    fun userInput(text: String) {
        val clean = OpenVikingCaptureSanitizer.clean(text) ?: return
        synchronized(userFacts) { if (userFacts.size < 32) userFacts += clean }
    }
    fun allowsExplicitStore(content: String): Boolean {
        if (!explicitStoreBlocked) return true
        val clean = OpenVikingCaptureSanitizer.clean(content) ?: return false
        // Even after retrieval, an exact span of the user's own clean input has known provenance.
        // A model paraphrase of remote/tool/screen data has no such proof and is refused.
        return synchronized(userFacts) { userFacts.any { it.contains(clean) } }
    }
    fun accept(event: AgentEvent) {
        when (event) {
            is AgentEvent.ToolStarted -> {
                // ToolStarted precedes dispatch. A store must not veto itself, but its result
                // still taints later automatic assistant capture. Any other tool blocks stores.
                if (event.name == "ov_memory_store") tainted = true else taint()
            }
            is AgentEvent.HostedToolStarted, is AgentEvent.SubagentProgress -> taint()
            is AgentEvent.RunFailed -> failed = true
            else -> Unit
        }
    }
}
