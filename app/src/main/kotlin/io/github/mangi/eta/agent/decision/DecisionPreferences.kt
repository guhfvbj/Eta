package io.github.mangi.eta.agent.decision

import org.json.JSONObject

internal class DecisionPreferences(private val settings: JSONObject) {
    fun getString(key: String, default: String = "") = settings.optString(key, default)
    fun getInt(key: String, default: Int) = settings.optInt(key, default)
    fun isEnabled(key: String) = settings.optBoolean(key, key == "decision_shadow")
    object Keys {
        const val AGENT_DECISION_MODEL_ENABLED = "decision_enabled"
        const val AGENT_DECISION_SHADOW = "decision_shadow"
        const val AGENT_DECISION_FAILURE_POLICY = "decision_failure"
        const val AGENT_DECISION_PROVIDER_ID = "decision_provider"
        const val AGENT_DECISION_MODEL_ID = "decision_model"
        const val AGENT_DECISION_HISTORY_TURNS = "decision_history"
        const val AGENT_DECISION_STICKY_TTL_TURNS = "decision_sticky"
        const val AGENT_DECISION_ENDPOINT_OVERRIDE = "decision_endpoint"
        const val AGENT_DECISION_ESCALATION_ENABLED = "decision_escalation_enabled"
        const val AGENT_DECISION_ESCALATION_MODEL_ID = "decision_upgrade_model"
        const val AGENT_DECISION_ESCALATION_PROVIDER_ID = "decision_upgrade_provider"
        const val AGENT_DECISION_ESCALATION_CONFIDENCE_PCT = "decision_confidence_pct"
        const val AGENT_DECISION_PROTOCOL = "decision_protocol"
        const val AGENT_DECISION_MODE = "decision_mode"
        const val AGENT_DECISION_TIMEOUT_MS = "decision_timeout"
        const val AGENT_DECISION_FAILURE_OPEN = "open"
        const val AGENT_DECISION_FAILURE_CLOSED = "closed"
        const val AGENT_DECISION_PROTOCOL_SYSTEMONE = "systemone"
        const val AGENT_DECISION_MODE_CONTROL = "control"
        const val AGENT_DECISION_MODE_ADVISE = "advise"
        const val AGENT_DECISION_HISTORY_TURNS_DEFAULT = 3
        const val AGENT_DECISION_STICKY_TTL_DEFAULT = 2
        const val AGENT_DECISION_HISTORY_TURNS_MAX = 10
        const val AGENT_DECISION_ESCALATION_CONFIDENCE_DEFAULT_PCT = 60
        const val AGENT_DECISION_TIMEOUT_DEFAULT_MS = 3000
    }
}
