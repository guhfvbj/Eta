package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.AgentFeaturePreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolGroups
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import org.json.JSONObject

internal object AgentDecisionConnectivity {
    data class Result(val ok: Boolean, val http: Int?, val latencyMs: Long, val endpoint: String,
        val model: String, val detail: String, val at: Long = System.currentTimeMillis())
    /** Deliberately bypasses router/cache/sticky/telemetry and never escalates. */
    suspend fun probeDecisionConnectivity(settings: JSONObject, controller: AgentRunController): Result {
        val config = AgentFeaturePreferences.model(settings.optString("decision_model"))
            ?: return Result(false, null, 0, "", "", "未选择判断模型或该文本模型不可用")
        val result = AgentDecisionEvaluator.evaluate(
            AgentRuntimeWire.RunRequest("connectivity-probe", "Hello", config, emptyList()),
            settings.optString("decision_model"), config.baseUrl, config.apiKey, settings.optInt("decision_timeout", 3000), 0,
            settings.optString("decision_endpoint"), listOf(AgentToolGroups.WEB_GROUP), emptySet(), modelName = config.model,
            runtimeConfig = config, protocol = settings.optString("decision_protocol", "llm"), controller = controller)
        return Result(result.succeeded, result.httpStatus, result.latencyMs, result.endpoint.orEmpty(), config.model,
            if (result.succeeded) "needs_tools=${result.needsTools}, groups=${result.selectedGroups}" else
                listOfNotNull(result.skipReason, result.failureReason, result.endpointFailure, result.detail).filter { it.isNotBlank() }.joinToString(" · "))
    }
}
