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
        assertTrue(AgentTokenEstimator.text("中文日本語한글".repeat(100)) >= 1_000)
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
        assertTrue(withOutput >= 108_000)
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
        assertTrue(projected >= ordinary + 8_000)
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
