package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.ModelFeatureJson

internal data class AgentDecisionJsonResult(val selected: Set<String>, val confidence: Double, val needsTools: Boolean)
/** Normalize model labels and inconsistent needs_tools while retaining the local group boundary. */
internal object AgentDecisionJson {
    fun parse(raw: String, available: Set<String>): AgentDecisionJsonResult {
        val json = ModelFeatureJson.objectFrom(raw)
        val selected = json.optJSONArray("groups") ?: error("判断缺少 groups")
        val names = (0 until selected.length()).mapNotNull { (selected.opt(it) as? String)?.trim() }
            .filter { it != "basic" && it in available }.toSet()
        val confidence = json.getDouble("confidence")
        require(confidence.isFinite() && confidence in 0.0..1.0) { "confidence 必须为 0–1 之间的有限数值" }
        if (json.has("needs_tools")) require(json.get("needs_tools") is Boolean) { "needs_tools 必须为布尔值" }
        val needsTools = json.optBoolean("needs_tools", names.isNotEmpty())
        return AgentDecisionJsonResult(names, confidence, needsTools || names.isNotEmpty())
    }
}
