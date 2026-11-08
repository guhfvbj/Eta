package io.github.mangi.eta.agent.tool

/** One decision table shared by the execution gate and its regression matrix. */
internal object AgentToolReviewPolicy {
    enum class Action { ALLOW, DENY, CONFIRM }
    fun decide(mode: String, risk: AgentRiskLevel, modelVerdict: Boolean? = null): Action = when {
        mode == "readonly" -> if (risk == AgentRiskLevel.LOW) Action.ALLOW else Action.DENY
        risk == AgentRiskLevel.LOW -> Action.ALLOW
        risk == AgentRiskLevel.FATAL -> Action.CONFIRM
        mode == "auto" && risk == AgentRiskLevel.MEDIUM -> Action.ALLOW
        mode == "smart" && modelVerdict != null -> if (modelVerdict) Action.ALLOW else Action.DENY
        else -> Action.CONFIRM
    }
}
