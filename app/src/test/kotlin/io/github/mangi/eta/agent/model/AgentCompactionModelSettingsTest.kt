package io.github.mangi.eta.agent.model

import android.content.Context
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.db.toModelEntities
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentCompactionModelSettingsTest {
    private lateinit var context: Context
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
        model = "main", systemPrompt = "", contextWindow = 256_000)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        context.getSharedPreferences("eta_agent_features", Context.MODE_PRIVATE).edit().clear().commit()
        ProviderRepository.init(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("eta_agent_features", Context.MODE_PRIVATE).edit().clear().commit()
        EtaDatabase.closeForTests()
    }

    @Test
    fun normalizationDisablesIncompleteReferencesAndTurningOffKeepsLastSelection() {
        assertFalse(AgentFeaturePreferences.load(context).optBoolean("compaction_custom"))
        for ((provider, model) in listOf("" to "", "provider" to "", "" to "model")) {
            val raw = JSONObject().put("migration", 3).put("compaction_custom", true)
                .put("compaction_custom_provider_id", provider).put("compaction_custom_model_id", model)
            context.getSharedPreferences("eta_agent_features", Context.MODE_PRIVATE).edit()
                .putString("settings", raw.toString()).commit()
            assertFalse(AgentFeaturePreferences.load(context).getBoolean("compaction_custom"))
            assertFalse(AgentFeaturePreferences.load(context).getBoolean("compaction_custom"))
        }
        AgentFeaturePreferences.set("compaction_custom_provider_id", "provider", context)
        AgentFeaturePreferences.set("compaction_custom_model_id", "model", context)
        AgentFeaturePreferences.set("compaction_custom", true, context)
        assertTrue(AgentFeaturePreferences.load(context).getBoolean("compaction_custom"))
        AgentFeaturePreferences.set("compaction_custom", false, context)
        assertEquals("provider", AgentFeaturePreferences.load(context).getString("compaction_custom_provider_id"))
        assertEquals("model", AgentFeaturePreferences.load(context).getString("compaction_custom_model_id"))
    }

    @Test
    fun enabledCustomProviderIsUsedOnlyForSummaryAndNeverForTheTriggerWindow() = runBlocking {
        val captured = AtomicReference<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            captured.set(JSONObject(exchange.requestBody.bufferedReader().readText()))
            val body = """{"error":{"code":"fixture_rejected"}}""".toByteArray()
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            storeProvider(OpenAiCompatibleProviderSetting(id = "summary-provider", name = "Summary",
                baseUrl = "http://127.0.0.1:${server.address.port}", models = listOf(Model(id = "summary-local",
                    modelId = "summary-8k", displayName = "Summary", contextWindowOverride = 8_192, reasoning = false))))
            selectCustom()
            for (current in listOf(config, config.copy(model = "subagent"))) {
                captured.set(null)
                val session = AgentContextSession(current, history(), 0, "test", fallbackProvider(current.model),
                    AgentRunController(), { emptySet() }, {}, {})
                session.observeInputTokens(10_000) // 超过 8k 摘要窗口，但远低于主模型 192k 阈值。
                session.compact()
                assertNull(captured.get())
                assertEquals("HTTP_400", assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }.code)
                val body = captured.get()
                assertEquals("summary-8k", body.getString("model"))
                assertTrue(body.getInt("max_tokens") <= 2_048)
                assertFalse(body.has("reasoning_effort"))
                assertFalse(body.has("tools"))
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun disabledDeletedAndNonTextSelectionsFallBackToCurrentOrSubagentModel() = runBlocking {
        for (current in listOf(config, config.copy(model = "subagent"))) {
            for (kind in listOf("follow", "provider-disabled", "model-disabled", "image", "deleted")) {
                val model = Model(id = "summary-local", modelId = "summary", displayName = "Summary",
                    isEnabled = kind != "model-disabled", outputModalities = if (kind == "image") listOf("image") else listOf("text"))
                storeProvider(OpenAiCompatibleProviderSetting(id = "summary-provider", name = "Summary",
                    baseUrl = "https://must-not-be-used.invalid", isEnabled = kind != "provider-disabled", models = listOf(model)))
                selectCustom()
                if (kind == "follow") AgentFeaturePreferences.set("compaction_custom", false, context)
                if (kind == "deleted") EtaDatabase.get(context).providerDao().deleteProvider("summary-provider")
                val result = AgentContextCompactor(current, fallbackProvider(current.model), AgentRunController())
                    .compact(history(), 0, emptySet())
                assertTrue(result.getJSONObject(0).optBoolean("_eta_context_summary"))
            }
        }
    }

    private suspend fun storeProvider(provider: OpenAiCompatibleProviderSetting) {
        EtaDatabase.get(context).providerDao().replaceProvider(provider.toEntity(), provider.toModelEntities())
    }

    private fun selectCustom() {
        AgentFeaturePreferences.set("compaction_custom_provider_id", "summary-provider", context)
        AgentFeaturePreferences.set("compaction_custom_model_id", "summary-local", context)
        AgentFeaturePreferences.set("compaction_custom", true, context)
    }

    private fun history() = JSONArray().put(AgentConversationCodec.userTextMessage("过去任务"))
        .put(JSONObject().put("role", "assistant").put("content", "历史事实".repeat(2_000)))
        .put(AgentConversationCodec.userTextMessage("当前任务"))

    private fun fallbackProvider(model: String) = object : AgentProviderClient {
        override val id = "fallback"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit): ProviderResponse {
            assertEquals(model, request.config.model)
            return ProviderResponse(JSONObject().put("role", "assistant").put("content", "已完成检查。")
                .put("finish_reason", "stop"))
        }
    }
}
