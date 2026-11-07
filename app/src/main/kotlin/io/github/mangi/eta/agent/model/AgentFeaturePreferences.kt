package io.github.mangi.eta.agent.model

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentAppContext
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import org.json.JSONObject

/** Feature references only: API credentials remain in the existing provider database. */
internal object AgentFeaturePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("eta_agent_features", Context.MODE_PRIVATE)
    fun load(context: Context = requireNotNull(AgentAppContext.resolve())): JSONObject =
        runCatching { JSONObject(prefs(context).getString("settings", "{}")!!) }.getOrDefault(JSONObject())
    @Synchronized fun set(key: String, value: Any, context: Context = requireNotNull(AgentAppContext.resolve())) {
        val settings = load(context).put(key, value)
        check(prefs(context).edit().putString("settings", settings.toString()).commit())
    }
    suspend fun model(id: String): AgentModelClient.ModelConfig? {
        if (id.isBlank()) return null
        val provider = ProviderRepository.providerByModelId(id)?.takeIf { it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == id && it.isEnabled } ?: return null
        return RuntimeConfigRepository.buildRuntimeConfig(provider, model)
    }
    fun manualTitle(id: String, context: Context) = prefs(context).getBoolean("manual_title_$id", false)
    fun protectTitle(id: String, context: Context) { prefs(context).edit().putBoolean("manual_title_$id", true).apply() }
}

internal data class ModelFeatureSelection(val custom: Boolean, val providerId: String, val modelId: String) {
    suspend fun resolve(): AgentModelClient.ModelConfig? {
        if (providerId.isBlank() || modelId.isBlank()) return null
        val provider = ProviderRepository.providerById(providerId)?.takeIf { it.isEnabled } ?: return null
        val model = provider.models.firstOrNull { it.id == modelId && it.isEnabled } ?: return null
        return RuntimeConfigRepository.buildRuntimeConfig(provider, model)
    }
}
