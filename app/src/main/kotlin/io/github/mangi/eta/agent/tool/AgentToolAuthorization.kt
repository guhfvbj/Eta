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
    private val queue = MutableStateFlow<List<Request>>(emptyList())
    val pending = queue.asStateFlow()
    @Synchronized fun answer(id: String, allowed: Boolean) {
        queue.value.firstOrNull { it.id == id }?.result?.complete(allowed)
    }
    fun confirm(conversation: String, tool: String, arguments: String, risk: AgentRiskAssessment,
        controller: AgentRunController, repeatAllowed: Boolean = false): Boolean {
        val request = Request(UUID.randomUUID().toString(), conversation, tool, arguments, risk, repeatAllowed, CompletableFuture())
        synchronized(this) { queue.value = queue.value + request }
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        try {
            while (System.nanoTime() < end) {
                controller.throwIfCancelled()
                if (request.result.isDone) return request.result.get()
                Thread.sleep(100)
            }
            return false
        } finally { synchronized(this) { queue.value = queue.value.filterNot { it.id == request.id } } }
    }
}

internal object RecipeRuntime {
    private val stores = mutableMapOf<String, AgentToolRecipeStore>()
    @Synchronized fun store(context: android.content.Context): AgentToolRecipeStore {
        val path = ToolRecipePaths.directory(context)
        return stores.getOrPut(path.absolutePath) { AgentToolRecipeStore(path) }
    }
}
