package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.data.db.ConversationMetadata
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel

/** UI 发送与后台恢复共用的模型、思考档位与本地权限解析边界。 */
internal object ConversationRuntimeConfigResolver {
    data class Target(val localModelId: String, val config: AgentModelClient.ModelConfig, val toolCall: Boolean?)
    data class Resolved(
        val config: AgentModelClient.ModelConfig,
        val resolvedModelId: String,
        val fellBackToDefault: Boolean,
        val supportsTools: Boolean,
    ) {
        // 后台续跑必须能用 terminal_job 接手任务；主动撤权和显式无工具模型均降级系统通知。
        val canWakeForTerminalJob: Boolean get() = supportsTools && config.terminalTools
    }

    suspend fun resolve(
        conversation: ConversationMetadata?,
        requestedEffort: ReasoningEffort? = null,
        preferences: SharedPreferences?,
        supportsSearchSuggestions: Boolean,
        findEnabled: suspend (String?) -> Target? = ::findEnabledTarget,
        defaultTarget: suspend () -> Target? = ::findDefaultTarget,
    ): Resolved? = resolve(
        modelId = conversation?.modelId,
        effort = requestedEffort ?: ReasoningEffort.fromWireValue(conversation?.reasoningEffort)
            ?: ReasoningEffort.fromLegacy(conversation?.thinkingEnabled == true),
        permissions = AgentRuntimePolicy.permissions(preferences),
        supportsSearchSuggestions = supportsSearchSuggestions,
        findEnabled = findEnabled,
        defaultTarget = defaultTarget,
    )

    /** 可注入启用模型和默认模型查找，纯 JVM 测试不访问 Prefs、DataStore 或 Room。 */
    suspend fun resolve(
        modelId: String?,
        effort: ReasoningEffort,
        permissions: AgentRuntimePolicy.Permissions,
        supportsSearchSuggestions: Boolean,
        findEnabled: suspend (String?) -> Target? = ::findEnabledTarget,
        defaultTarget: suspend () -> Target? = ::findDefaultTarget,
    ): Resolved? {
        val selected = findEnabled(modelId)
        val target = selected ?: defaultTarget() ?: return null
        // 与原 UI 相同：模型无 reasoningCapabilities 时关闭；全局关闭优先于 mandatory 归一化。
        val normalized = if (!permissions.thinking) ReasoningEffort.OFF
            else target.config.reasoningCapabilities?.normalize(effort) ?: ReasoningEffort.OFF
        val config = AgentRuntimePolicy.constrain(target.config.copy(
            terminalTools = permissions.terminalTools,
            browserTools = permissions.browserTools,
            deviceDirectTools = permissions.deviceDirectTools,
            deviceSensitiveReadTools = permissions.deviceSensitiveReadTools,
            deviceSensitiveActionTools = permissions.deviceSensitiveActionTools,
            subagentTools = permissions.subagentTools,
            reasoningEffort = normalized,
            thinkingEnabled = normalized.enablesReasoning,
            supportsSearchSuggestions = supportsSearchSuggestions,
        ), permissions)
        return Resolved(config, target.localModelId, fellBackToDefault = selected == null,
            // supportsTools 是目录中“已确认支持”的徽标（== true）；现有 AgentModelClient/Provider
            // 不用它过滤工具。null 的手工/未知模型仍允许发送工具，只有 false 明确禁用。
            supportsTools = target.toolCall != false)
    }

    private suspend fun findEnabledTarget(id: String?): Target? {
        ProviderRepository.ensureBuiltInsMerged()
        return ProviderRepository.allProviders().enabledModel(id)?.let { (provider, model) ->
            Target(model.id, RuntimeConfigRepository.buildRuntimeConfig(provider, model), model.toolCall)
        }
    }

    private suspend fun findDefaultTarget(): Target? = RuntimeConfigRepository.currentRuntimeConfig()?.let { config ->
        ProviderRepository.allProviders().firstOrNull { it.id == config.providerId && it.isEnabled }
            ?.models?.firstOrNull { it.isEnabled && it.modelId.trim() == config.model }?.let { model ->
                Target(model.id, config, model.toolCall)
            }
    }
}
