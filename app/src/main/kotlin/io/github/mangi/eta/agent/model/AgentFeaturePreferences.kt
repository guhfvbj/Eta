package io.github.mangi.eta.agent.model

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import org.json.JSONObject

/** Feature references only: API credentials remain in the existing provider database. */
internal object AgentFeaturePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("eta_agent_features", Context.MODE_PRIVATE)
    fun load(context: Context = requireNotNull(AgentAppContext.resolve())): JSONObject = synchronized(this) {
        val settings = runCatching { JSONObject(prefs(context).getString("settings", "{}")!!) }.getOrDefault(JSONObject())
        if (settings.optInt("migration", 0) < 2) {
            settings.keys().asSequence().toList().filter { it.startsWith("group_") || it == "disabled_tools" }.forEach(settings::remove)
            if (settings.optString("loading") == "tool") settings.put("loading", "search")
            if (!settings.has("title_custom")) settings.put("title_custom", settings.optString("title_model").isNotBlank())
            if (!settings.has("title_custom_model_id")) settings.put("title_custom_model_id", settings.optString("title_model"))
            if (!settings.has("decision_escalation_enabled")) settings.put("decision_escalation_enabled", settings.optString("decision_upgrade_model").isNotBlank())
            settings.put("migration", 2)
            check(prefs(context).edit().putString("settings", settings.toString()).commit())
            context.getSharedPreferences("eta_tool_sessions", Context.MODE_PRIVATE).edit().clear().apply()
        }
        var normalized = false
        settings.keys().asSequence().toList().filter { it.startsWith("review_") }.forEach {
            settings.remove(it)
            normalized = true
        }
        if (settings.optInt("migration") < 3) {
            runCatching { context.getSystemService(android.app.NotificationManager::class.java)
                ?.deleteNotificationChannel("eta_tool_authorization") }
            settings.put("migration", 3)
            normalized = true
        }
        fun choice(key: String, allowed: Set<String>, fallback: String) {
            if(settings.has(key) && settings.optString(key) !in allowed) { settings.put(key,fallback); normalized = true }
        }
        choice("loading",setOf("search","group","off"),"search")
        choice("decision_protocol",setOf("llm","systemone"),"llm")
        choice("decision_mode",setOf("control","advise"),"control")
        if(settings.has("decision_timeout") && settings.optInt("decision_timeout") !in 1000..10000) { settings.put("decision_timeout",settings.optInt("decision_timeout",3000).coerceIn(1000,10000)); normalized = true }
        if(!settings.has("decision_confidence_pct") && settings.has("decision_confidence")) {
            settings.put("decision_confidence_pct", (settings.optDouble("decision_confidence",.6)*100).toInt().coerceIn(0,100)); normalized = true
        }
        if(normalized) check(prefs(context).edit().putString("settings",settings.toString()).commit())
        settings
    }
    @Synchronized fun set(key: String, value: Any, context: Context = requireNotNull(AgentAppContext.resolve())) {
        val settings = load(context).put(key, value)
        check(prefs(context).edit().putString("settings", settings.toString()).commit())
    }
    suspend fun model(id: String): AgentModelClient.ModelConfig? {
        if (id.isBlank()) return null
        val provider = ProviderRepository.providerByModelId(id)?.takeIf { it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == id && it.isEnabled && isTextModel(it) } ?: return null
        return RuntimeConfigRepository.buildRuntimeConfig(provider, model)
    }
    fun exportAll(context: Context): String {
        load(context)
        return JSONObject(prefs(context).all).toString()
    }
    fun validateImport(raw: String) {
        require(raw.length <= 1024 * 1024) { "功能配置过大" }
        val json = JSONObject(raw)
        for (key in json.keys()) require(json.opt(key) is String || json.opt(key) is Boolean || json.opt(key) is Number)
        if (json.has("settings")) JSONObject(json.getString("settings"))
    }
    fun restoreAll(raw: String, context: Context) {
        validateImport(raw)
        val json = JSONObject(raw)
        val editor = prefs(context).edit().clear()
        for(key in json.keys()) when(val entry = json.get(key)) {
            is Boolean -> editor.putBoolean(key,entry)
            is String -> editor.putString(key,entry)
            is Int -> editor.putInt(key,entry)
            is Number -> editor.putLong(key,entry.toLong())
        }
        check(editor.commit()) { "功能配置恢复失败" }
        clearToolSessions(context)
    }
    fun isTextModel(model: io.github.mangi.eta.data.model.Model): Boolean = model.outputModalities.all { it.equals("text", true) } &&
        !Regex("(?i)(?:^|[-_/])(tts|speech|whisper|dall-e|imagen|image|imagine|imagegen|video|audio|sora|veo|flux|cosyvoice|sensevoice)(?:$|[-_./])").containsMatchIn(model.modelId)
    fun clearConversation(id: String, context: Context) {
        val editor = prefs(context).edit()
        listOf("manual_title_", "generated_title_").forEach { editor.remove(it + id) }
        editor.apply()
        val sessions = context.getSharedPreferences("eta_tool_sessions", Context.MODE_PRIVATE)
        sessions.edit().remove("mode_$id").remove("opened_$id").remove("groups_$id").apply()
    }
    fun clearToolSessions(context: Context) = context.getSharedPreferences("eta_tool_sessions", Context.MODE_PRIVATE).edit().clear().putLong("generation",System.currentTimeMillis()).apply()
    fun resetGeneratedTitleForEdit(id: String, context: Context): Boolean {
        if(manualTitle(id,context)) return false
        prefs(context).edit().remove("generated_title_$id").apply()
        return true
    }
    fun manualTitle(id: String, context: Context) = prefs(context).getBoolean("manual_title_$id", false)
    fun protectTitle(id: String, context: Context) { prefs(context).edit().putBoolean("manual_title_$id", true).apply() }
}

internal data class ModelFeatureSelection(val custom: Boolean, val providerId: String, val modelId: String) {
    suspend fun resolve(): AgentModelClient.ModelConfig? {
        if (providerId.isBlank() || modelId.isBlank()) return null
        val provider = ProviderRepository.providerById(providerId)?.takeIf { it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == modelId && it.isEnabled && AgentFeaturePreferences.isTextModel(it) } ?: return null
        return RuntimeConfigRepository.buildRuntimeConfig(provider, model)
    }
}
