package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.core.EtaTrace
import org.json.JSONObject

/** Successful effects outside this turn's selected/sticky groups are misses, not failed attempts. */
internal object AgentDecisionMismatch {
    fun record(plan: AgentDecisionPlan, conversation: String, group: String, result: String) {
        if (plan.failureReason != null || plan.skippedForContinuation || group == "basic" || group in plan.selectedGroups + plan.stickyGroups) return
        val json = runCatching { JSONObject(result) }.getOrNull() ?: return
        if (!json.optBoolean("ok", false)) return
        AgentDecisionTelemetry.recordMismatch(conversation)
        EtaTrace.log("decision_mismatch", "conversation=$conversation group=$group mode=${plan.mode}")
    }
}
