package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray

/** Bounded auxiliary request using existing provider/auth implementations, with no tools. */
internal object ModelFeatureCompletion {
    fun complete(config: AgentModelClient.ModelConfig, messages: JSONArray, controller: AgentRunController,
        sessionId: String, timeoutMs: Long = 30_000, outputLimit: Int = 1024,
        usageConversationId: String = sessionId, onUsage: (org.json.JSONObject?) -> Unit = {}, providerOverride: AgentProviderClient? = null): String {
        val child = AgentRunController()
        val binding = controller.register { child.cancel() }
        val deadline = Thread {
            try { Thread.sleep(timeoutMs); child.cancel() } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
        try {
            controller.throwIfCancelled()
            val bounded = io.github.mangi.eta.agent.runtime.AgentRuntimePolicy.withoutOptionalThinking(config).copy(hostedWebSearchEnabled = false,
                systemPrompt = "", extraBodyJson = "", customBody = emptyList())
            val response = AgentModelRetry().complete(
                1, ProviderRequest(bounded, messages, JSONArray(), sessionId, ProviderRequestPurpose.COMPACTION, outputLimit),
                providerOverride ?: ProviderClientFactory.getClient(bounded), child, {}, { _, _ -> }, {},
            ).response
            controller.throwIfCancelled()
            check(!child.isCancelled) { "辅助模型超时" }
            check(response.stopReason == AssistantStopReason.END_TURN) { "辅助模型未完整返回" }
            check((response.assistantMessage.optJSONArray("tool_calls")?.length() ?: 0) == 0)
            onUsage(response.assistantMessage.optJSONObject("usage"))
            return ModelFeatureJson.withoutThinking(response.assistantMessage.optString("content")).also { require(it.isNotEmpty() && it.length <= outputLimit * 16) }
        } catch (failure: Exception) {
            controller.throwIfCancelled()
            if (child.isCancelled) throw IllegalStateException("辅助模型超时（${timeoutMs}ms）", failure)
            throw failure
        } finally { deadline.interrupt(); child.cancel(); binding.close() }
    }
}
