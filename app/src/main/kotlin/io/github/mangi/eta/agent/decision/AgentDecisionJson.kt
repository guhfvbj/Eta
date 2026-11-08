package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.ModelFeatureJson

internal data class AgentDecisionJsonResult(val selected: Set<String>, val confidence: Double, val needsTools: Boolean)
/** Strict JSON result, extracted independently of explanatory prose or optional reasoning. */
internal object AgentDecisionJson {
    fun parse(raw: String, available: Set<String>): AgentDecisionJsonResult {
        val json = ModelFeatureJson.objectFrom(raw)
        val selected = json.optJSONArray("groups") ?: error("判断缺少 groups")
        val names = (0 until selected.length()).map { selected.getString(it) }.toSet()
        require(available.containsAll(names)) { "判断返回未知工具组" }
        val confidence = json.getDouble("confidence")
        require(confidence.isFinite() && confidence in 0.0..1.0)
        if (json.has("needs_tools")) require(json.get("needs_tools") is Boolean) { "needs_tools 必须为布尔值" }
        val needsTools = json.optBoolean("needs_tools", names.isNotEmpty())
        require(needsTools || names.isEmpty()) { "判断结果自相矛盾" }
        return AgentDecisionJsonResult(names, confidence, needsTools)
    }
}
