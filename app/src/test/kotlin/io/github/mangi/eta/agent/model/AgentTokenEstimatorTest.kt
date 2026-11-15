package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentTokenEstimatorTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture",
        model = "fixture", systemPrompt = "system", contextWindow = 256_000)

    @Test
    fun textJsonCodeCjkAndImagesHaveConservativeBoundedEstimates() {
        assertTrue(AgentTokenEstimator.text("中文日本語한글".repeat(100)) >= 700)
        assertTrue(AgentTokenEstimator.text("val x = foo(\"bar\");\n".repeat(100)) >= 700)
        val tools = JSONArray("""[{"type":"function","function":{"name":"fixture","parameters":{"type":"object"}}}]""")
        assertTrue(AgentTokenEstimator.json(tools) > 20)
        val image = JSONObject().put("type", "image_url").put("image_url", JSONObject()
            .put("url", "data:image/png;base64," + "A".repeat(100_000)))
        assertEquals(8_192, AgentTokenEstimator.json(image)) // encoded bytes are not text tokens
        assertEquals(0, AgentTokenEstimator.text(""))
        assertEquals(Int.MAX_VALUE, AgentTokenEstimator.bounded(Long.MAX_VALUE))
    }

    @Test
    fun cjkAsciiAndJsonSymbolsUseExplicitWeights() {
        assertEquals(6, AgentTokenEstimator.text("中文漢字测试"))
        assertEquals(2, AgentTokenEstimator.text("abc123"))
        assertEquals(4, AgentTokenEstimator.text("{}[]:,"))
        assertEquals(24, AgentTokenEstimator.text("{\"name\":\"中文\",\"n\":123}".repeat(2)))
    }

    @Test
    fun observedUsageCalibratesLocalFallbackAndOldBaselinesKeepDefaults() {
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("中".repeat(4_000)))
        val observed = session(messages, systemCount = 0)
        val local = observed.effectiveTokens()
        observed.captureRequest()
        observed.observeInputTokens(local / 2)
        observed.checkpoint()
        val saved = AgentContextSnapshot.decode(observed.snapshot()!!.encode())!!.tokenBaseline!!
        assertEquals(0.75, saved.estimateScale, 0.001)
        assertEquals(local / 2, saved.lastContextTokens)
        messages.getJSONObject(0).put("content", "文".repeat(4_000)) // 指纹改变后仍沿用已学到的系数。
        assertFalse(saved.matches(AgentConversationCodec.transcript(messages, 0), config))
        val fallback = session(messages, saved, systemCount = 0).effectiveTokens()
        assertEquals((local * saved.estimateScale).toInt(), fallback)
        val legacy = kotlinx.serialization.json.Json.decodeFromString<AgentContextTokenBaseline>(
            """{"lastContextTokens":1,"messageCount":0,"fingerprint":"","prefixTokens":0,"modelKey":""}""",
        )
        assertEquals(1.0, legacy.estimateScale, 0.0)
        assertEquals(0, legacy.compactionCooldownRemaining)
        assertEquals(0.1, saved.copy(estimateScale = -10.0).boundedEstimateScale, 0.0)
        assertEquals(2.0, saved.copy(estimateScale = 100.0).boundedEstimateScale, 0.0)
    }

    @Test
    fun observedBaselineAddsNewMessagesToolsAndSystemChangesAndSurvivesEncoding() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "system"))
            .put(AgentConversationCodec.userTextMessage("initial"))
        val session = session(messages)
        session.prepareRequest(JSONArray())
        session.captureRequest()
        session.observeInputTokens(100_000)
        assertEquals(100_000, session.effectiveTokens())
        messages.put(JSONObject().put("role", "assistant").put("content", "中文代码".repeat(1_000)))
        val withOutput = session.effectiveTokens()
        assertTrue(withOutput >= 104_000)
        val tools = JSONArray().put(JSONObject().put("description", "tool contract ".repeat(200)))
        session.prepareRequest(tools)
        assertTrue(session.effectiveTokens() > withOutput)
        messages.getJSONObject(0).put("content", "新增系统提示".repeat(200))
        val expected = session.effectiveTokens()
        session.checkpoint()
        val saved = AgentContextSnapshot.decode(session.snapshot()!!.encode())!!
        assertEquals(expected, saved.lastContextTokens)
        val restored = session(JSONArray(messages.toString()), saved.tokenBaseline)
        restored.prepareRequest(tools)
        assertEquals(expected, restored.effectiveTokens())
        messages.put(AgentConversationCodec.userTextMessage("继续"))
        val continued = session(JSONArray(messages.toString()), saved.tokenBaseline)
        continued.prepareRequest(tools)
        assertTrue(continued.effectiveTokens() > expected)
    }

    @Test
    fun legacyPrefixWeightsAreRebasedWithoutSubtractingRealObservedUsage() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "中文提示".repeat(2_000)))
            .put(AgentConversationCodec.userTextMessage("历史正文"))
        val history = AgentConversationCodec.transcript(messages, 1)
        val legacyPrefix = AgentTokenEstimator.messages(messages, legacy = true) -
            AgentTokenEstimator.messages(history, legacy = true) + AgentTokenEstimator.json(JSONArray())
        val old = AgentContextTokenBaseline(36_885, history.size, AgentTokenEstimator.fingerprint(history),
            legacyPrefix, AgentTokenEstimator.modelKey(config))
        assertEquals(0, old.estimatorVersion)
        val restored = session(messages, old)
        restored.prepareRequest(JSONArray())
        assertEquals(36_885, restored.effectiveTokens())
        restored.checkpoint()
        val rebased = AgentContextSnapshot.decode(restored.snapshot()!!.encode())!!.tokenBaseline!!
        assertEquals(AgentTokenEstimator.VERSION, rebased.estimatorVersion)
        assertTrue(rebased.prefixTokens < legacyPrefix)
        val restarted = session(JSONArray(messages.toString()), rebased)
        restarted.prepareRequest(JSONArray())
        assertEquals(36_885, restarted.effectiveTokens())
    }

    @Test
    fun editingHistoryInvalidatesBaselineEvenWhenMessageCountIsUnchanged() {
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("old"))
        val session = session(messages, systemCount = 0)
        session.observeInputTokens(1_000_000)
        session.checkpoint()
        val saved = session.snapshot()!!.tokenBaseline!!
        messages.put(0, AgentConversationCodec.userTextMessage("edited"))
        val edited = session(messages, saved, systemCount = 0)
        assertTrue(edited.effectiveTokens() < 100)
        assertFalse(saved.matches(AgentConversationCodec.transcript(messages, 0), config))
    }

    @Test
    fun projectedSystemContextIsIncludedInTheRequestEstimate() {
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("initial"))
        val ordinary = session(messages, systemCount = 0).effectiveTokens()
        val projected = session(messages, systemCount = 0, projection = { source ->
            JSONArray(source.toString()).put(JSONObject().put("role", "system")
                .put("content", "世界设定".repeat(1_000)))
        }).effectiveTokens()
        assertTrue(projected >= ordinary + 4_000)
    }

    @Test
    fun temporaryImageRemovalDoesNotStayInPersistedObservedUsage() {
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("initial"))
        messages.put(AgentConversationCodec.userMessage("observation", listOf(
            AgentModelClient.ModelImage("data:image/png;base64,AAAA", "image/png", 3),
        )).put("_eta_observation", true))
        val session = session(messages, systemCount = 0)
        session.captureRequest()
        messages.remove(1)
        session.observeInputTokens(10_000)
        assertTrue(session.effectiveTokens() in 1_000..2_000)
    }

    private fun session(messages: JSONArray, baseline: AgentContextTokenBaseline? = null, systemCount: Int = 1,
        projection: (JSONArray) -> JSONArray = AssistantScreenContextProjection::project,
    ) =
        AgentContextSession(config, messages, systemCount, "test", object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit): ProviderResponse = error("estimation must not call provider")
        }, AgentRunController(), { emptySet() }, {}, {}, initialTokenBaseline = baseline, requestProjection = projection)
}
