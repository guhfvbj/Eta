package io.github.mangi.eta.data.repository

import android.content.SharedPreferences
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.db.ConversationMetadata
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConversationRuntimeConfigResolverTest {
    private val capabilities = ModelReasoningCapabilities(
        supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), canDisable = true)
    private val a = ConversationRuntimeConfigResolver.Target("a", AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "model-a", systemPrompt = "",
        reasoningCapabilities = capabilities), toolCall = true)
    private val permissions = AgentRuntimePolicy.Permissions(terminalTools = true, browserTools = true,
        thinking = true, deviceDirectTools = true, deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = true, subagentTools = true)

    private fun conversation(effort: ReasoningEffort, modelId: String? = "a", thinking: Boolean = effort.enablesReasoning) =
        ConversationMetadata(id = "c", title = "title", thinkingEnabled = thinking, reasoningEffort = effort.wireValue,
            appliedRuntimeRunIdsJson = "[]", createdAt = 1, updatedAt = 1, modelId = modelId)

    private fun preferences(thinking: Boolean): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getBoolean" -> if (args!![0] == Prefs.Keys.AGENT_THINKING_ENABLED) thinking else args[1]
            else -> error("Unexpected preference method ${method.name}")
        }
    } as SharedPreferences

    @Test fun wakeInheritsPersistedHighLowAndOffAndGlobalDisableWins() = runBlocking {
        for (effort in listOf(ReasoningEffort.HIGH, ReasoningEffort.LOW, ReasoningEffort.OFF)) {
            for (enabled in listOf(true, false)) {
                val result = ConversationRuntimeConfigResolver.resolve(conversation(effort),
                    preferences = preferences(enabled), supportsSearchSuggestions = true,
                    findEnabled = { a }, defaultTarget = { error("unexpected fallback") })!!
                assertEquals(if (enabled) effort else ReasoningEffort.OFF, result.config.effectiveReasoningEffort)
                assertEquals(enabled && effort.enablesReasoning, result.config.thinkingEnabled)
                assertEquals("a", result.resolvedModelId)
                assertFalse(result.fellBackToDefault)
            }
        }
    }

    @Test fun currentModelCapabilitiesNormalizePersistedAndRequestedEfforts() = runBlocking {
        val b = a.copy(localModelId = "b", config = a.config.copy(model = "model-b",
            reasoningCapabilities = capabilities.copy(supportedEfforts = listOf(ReasoningEffort.MEDIUM))))
        val result = ConversationRuntimeConfigResolver.resolve(conversation(ReasoningEffort.HIGH, "b"),
            preferences = preferences(true), supportsSearchSuggestions = true,
            findEnabled = { id -> if (id == "b") b else a }, defaultTarget = { a })!!
        assertEquals("b", result.resolvedModelId)
        assertEquals("model-b", result.config.model)
        assertEquals(ReasoningEffort.MEDIUM, result.config.effectiveReasoningEffort)
        val requested = ConversationRuntimeConfigResolver.resolve(conversation(ReasoningEffort.LOW),
            requestedEffort = ReasoningEffort.HIGH, preferences = preferences(true), supportsSearchSuggestions = true,
            findEnabled = { a }, defaultTarget = { null })!!
        assertEquals(ReasoningEffort.HIGH, requested.config.effectiveReasoningEffort)
    }

    @Test fun removedDisabledOrUnboundModelFallsBackAndRecordsActualIdentity() = runBlocking {
        for (missing in listOf("deleted", "disabled", "", null)) {
            val result = ConversationRuntimeConfigResolver.resolve(missing, ReasoningEffort.HIGH, permissions, false,
                findEnabled = { null }, defaultTarget = { a })!!
            assertEquals("a", result.resolvedModelId)
            assertEquals("model-a", result.config.model)
            assertTrue(result.fellBackToDefault)
        }
        assertNull(ConversationRuntimeConfigResolver.resolve("missing", ReasoningEffort.HIGH, permissions, false,
            findEnabled = { null }, defaultTarget = { null }))
    }

    @Test fun localToolPermissionsAndNoReasoningCapabilitiesMatchRuntimePolicy() = runBlocking {
        val denied = permissions.copy(terminalTools = false, deviceSensitiveReadTools = false, subagentTools = false)
        val result = ConversationRuntimeConfigResolver.resolve("a", ReasoningEffort.HIGH, denied, true,
            findEnabled = { a }, defaultTarget = { null })!!
        assertEquals(result.config, AgentRuntimePolicy.constrain(result.config, denied))
        assertFalse(result.config.terminalTools)
        assertFalse(result.config.deviceSensitiveReadTools)
        assertFalse(result.config.subagentTools)
        assertTrue(result.config.browserTools)
        assertTrue(result.config.deviceDirectTools)
        assertTrue(result.config.deviceSensitiveActionTools)
        assertTrue(result.config.supportsSearchSuggestions)
        assertFalse(result.canWakeForTerminalJob)
        val plain = ConversationRuntimeConfigResolver.resolve("a", ReasoningEffort.HIGH, permissions, false,
            findEnabled = { a.copy(config = a.config.copy(reasoningCapabilities = null)) }, defaultTarget = { null })!!
        assertEquals(ReasoningEffort.OFF, plain.config.effectiveReasoningEffort)
    }

    @Test fun explicitNoToolsCannotWakeAndUnknownManualCapabilityPreservesExistingRuntimeSemantics() = runBlocking {
        for (toolCall in listOf(false, true, null)) {
            val result = ConversationRuntimeConfigResolver.resolve("a", ReasoningEffort.LOW, permissions, false,
                findEnabled = { a.copy(toolCall = toolCall) }, defaultTarget = { null })!!
            assertEquals(toolCall != false, result.supportsTools)
            assertEquals(toolCall != false, result.canWakeForTerminalJob)
        }
    }
}
