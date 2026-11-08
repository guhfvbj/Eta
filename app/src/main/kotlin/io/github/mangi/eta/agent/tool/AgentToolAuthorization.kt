package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.runtime.AgentRunController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Per-call confirmation, never a model-written 'approved' argument. */
internal object AgentToolAuthorization {
    data class Request(val id: String, val conversation: String, val tool: String, val arguments: String,
        val risk: AgentRiskAssessment, val repeatAllowed: Boolean, internal val result: CompletableFuture<Boolean>)
    private val visibleHosts = mutableSetOf<String>()
    @Synchronized fun setHostVisible(id: String, visible: Boolean) { if(visible) visibleHosts += id else visibleHosts -= id }
    @Synchronized private fun hasVisibleHost() = visibleHosts.isNotEmpty()
    private val grants = object : LinkedHashMap<String, Long>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }
    private fun signature(conversation: String, tool: String, arguments: String, policy: String): String =
        "$conversation\u0000$tool\u0000$arguments\u0000$policy"
    @Synchronized fun hasGrant(conversation: String, tool: String, arguments: String, policy: String): Boolean {
        grants.entries.removeAll { it.value <= System.currentTimeMillis() }
        return grants.containsKey(signature(conversation, tool, arguments, policy))
    }
    @Synchronized fun grant(conversation: String, tool: String, arguments: String, policy: String) {
        grants[signature(conversation, tool, arguments, policy)] = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(60)
    }
    @Synchronized fun clearConversation(conversation: String) { grants.keys.removeAll { it.startsWith("$conversation\u0000") } }
    @Synchronized fun clearGrants() { grants.clear() }
    private val queue = MutableStateFlow<List<Request>>(emptyList())
    val pending = queue.asStateFlow()
    @Synchronized fun answer(id: String, allowed: Boolean) {
        queue.value.firstOrNull { it.id == id }?.result?.complete(allowed)
    }
    fun confirm(conversation: String, tool: String, arguments: String, risk: AgentRiskAssessment,
        controller: AgentRunController, repeatAllowed: Boolean = false,
        presentNotification: (Request) -> Boolean = AuthorizationNotification::show): Boolean {
        val request = Request(UUID.randomUUID().toString(), conversation, tool, arguments, risk, repeatAllowed, CompletableFuture())
        synchronized(this) { queue.value = queue.value + request }
        val notified = presentNotification(request)
        if (!notified && !hasVisibleHost()) {
            synchronized(this) { queue.value = queue.value.filterNot { it.id == request.id } }
            io.github.mangi.eta.core.EtaTrace.log("tool_authorization", "unavailable=no_visible_host_or_notification tool=$tool")
            return false
        }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        try {
            while (System.nanoTime() < end) {
                controller.throwIfCancelled()
                if (request.result.isDone) return request.result.get()
                Thread.sleep(100)
            }
            return false
        } finally { AuthorizationNotification.cancel(request); synchronized(this) { queue.value = queue.value.filterNot { it.id == request.id } } }
    }
}
