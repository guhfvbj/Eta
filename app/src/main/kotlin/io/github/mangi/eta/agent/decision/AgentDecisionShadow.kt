package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolGroup
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.core.EtaTrace
import org.json.JSONObject
import java.util.concurrent.*

/** Same evaluator and telemetry as control, but never applies a plan, sticky update or prompt change. */
internal object AgentDecisionShadow {
    private val executor = ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        ThreadFactory { task -> Thread(task, "eta-decision-shadow").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    fun run(request: AgentRuntimeWire.RunRequest, config: AgentModelClient.ModelConfig, logger: AgentLogger,
        groups: List<AgentToolGroup>, settings: JSONObject, controller: AgentRunController) {
        try {
            executor.execute {
                runCatching {
                    val result = AgentDecisionRouter.route(request, config, logger, groups, settings, controller, observeOnly = true)
                    EtaTrace.log("decision.shadow", "run=${request.runId} selected=${result?.selectedGroups} latency_ms=${AgentDecisionDiagnostics.last?.latencyMs} fallback=${result?.failureReason}")
                }.onFailure { EtaTrace.log("decision.shadow", "failure=${it.javaClass.simpleName}") }
            }
        } catch (_: RejectedExecutionException) { EtaTrace.log("decision.shadow", "skipped=queue_full") }
    }
}
