package io.github.mangi.eta.data.provider

import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialModelCatalogTest {
    @Test
    fun deepseekPresetsAndUnconfiguredCustomFamilyUse128KWhileGeminiOverridesStayIntact() {
        val models = BuiltinProviders.PROVIDERS.flatMap(OfficialModelCatalog::modelsForProvider)
        val deepseek = models.filter { it.id in setOf("builtin-deepseek-flash", "builtin-deepseek-v4-pro",
            "builtin-bailian-deepseek-v4-1-flash") }
        assertEquals(3, deepseek.size)
        assertTrue(deepseek.all { it.contextWindow == 131_072 })
        fun config(model: String, window: Int? = null) = AgentModelClient.ModelConfig(
            baseUrl = "https://fixture.invalid", apiKey = "fixture", model = model, systemPrompt = "", contextWindow = window)
        assertEquals(131_072, config("DeepSeek-V4.1-Flash").knownContextWindow)
        assertEquals(131_072, config("vendor/deepseek-flash").knownContextWindow)
        assertEquals(64_000, config("deepseek-flash", 64_000).knownContextWindow)
        assertNull(config("unrecognized-model").knownContextWindow)
        assertNull(config("deepseek-flash", 0).knownContextWindow)
        assertEquals(256_000, config("gemini-4-hydrogen").knownContextWindow)
        assertEquals(256_000, config("gemini-4-argon-high", 256_000).knownContextWindow)
        assertEquals(600_000, config("gemini-4-hydrogen", 600_000).knownContextWindow)
        // Only DeepSeek catalog errors are repaired; Gemini catalog and user windows are retained.
        assertTrue(models.filter { it.modelId.startsWith("gemini-4-") }.all { it.contextWindow!! >= 256_000 })
    }

    @Test
    fun otherCatalogWindowsRemainMetadataUntilTheUserConfiguresAWindow() {
        val model = OfficialModelCatalog.modelsForProvider(BuiltinProviders.providerById(BuiltinProviders.DEEPSEEK_ID)!!).first()
        assertTrue(model.contextWindow!! > 0)
        assertNull(model.effectiveContextWindow)
        assertEquals(64_000, model.copy(contextWindowOverride = 64_000).effectiveContextWindow)
    }

    @Test
    fun openAiPresetsHaveTheDocumentedWindowConfiguredForRuntime() {
        val models = OfficialModelCatalog.modelsForProvider(BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID)!!)
        assertEquals(setOf("gpt-6.1-sol", "gpt-6-luna", "gpt-6-astra"), models.mapTo(mutableSetOf()) { it.modelId })
        models.forEach { model ->
            assertEquals(1_050_000, model.contextWindow)
            assertEquals(1_050_000, model.effectiveContextWindow)
            assertEquals(64_000, model.copy(contextWindowOverride = 64_000).effectiveContextWindow)
        }
    }

    @Test
    fun revisionOnlyOffersNewModelsAndEveryModelIdIsUnique() {
        BuiltinProviders.PROVIDERS.forEach { provider ->
            val models = OfficialModelCatalog.modelsForProvider(provider)
            val added = OfficialModelCatalog.modelsAddedSince(provider, revision = 0)

            assertEquals(models.size, models.map { it.modelId.lowercase() }.toSet().size)
            assertEquals(models.indices.toList(), models.map { it.sortOrder })
            assertTrue(models.all { it.source == ModelSource.CATALOG })
            assertTrue(added.all { addition -> models.any { it.modelId == addition.modelId } })
            assertTrue(OfficialModelCatalog.modelsAddedSince(provider, OfficialModelCatalog.CURRENT_REVISION).isEmpty())
        }
    }

    @Test
    fun newPresetsUseCurrentPlatformIdsAndCapabilities() {
        fun models(providerId: String) = OfficialModelCatalog.modelsForProvider(
            BuiltinProviders.providerById(providerId)!!
        ).associateBy { it.modelId }

        assertEquals(setOf("gpt-6.1-sol", "gpt-6-luna", "gpt-6-astra"), models(BuiltinProviders.OPENAI_ID).keys)
        assertEquals(1_050_000, models(BuiltinProviders.OPENAI_ID).getValue("gpt-6.1-sol").contextWindow)
        assertEquals(1_050_000, models(BuiltinProviders.OPENAI_ID).getValue("gpt-6-astra").contextWindow)
        assertTrue(models(BuiltinProviders.ANTHROPIC_ID).getValue("claude-opus-5-5").supportsTools)
        assertTrue(models(BuiltinProviders.BAILIAN_ID).getValue("qwen3.8-flash").supportsVision)
        assertFalse("video" in models(BuiltinProviders.BAILIAN_ID).getValue("kimi-k3").inputModalities)
        assertTrue(models(BuiltinProviders.DEEPSEEK_ID).getValue("deepseek-flash").supportsVision)
        assertFalse("deepseek-v4-flash" in models(BuiltinProviders.DEEPSEEK_ID))
        assertFalse("kimi-k2.5" in models(BuiltinProviders.KIMI_ID))
        assertTrue("audio" in models(BuiltinProviders.MIMO_ID).getValue("mimo-v2.6-pro").inputModalities)
        assertTrue("video" in models(BuiltinProviders.MINIMAX_ID).getValue("MiniMax-M3").inputModalities)
        assertEquals(1_000_000, models(BuiltinProviders.STEPFUN_ID).getValue("step-5-preview").contextWindow)
    }
}
