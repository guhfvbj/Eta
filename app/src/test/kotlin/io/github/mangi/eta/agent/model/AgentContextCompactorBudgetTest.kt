package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextCompactorBudgetTest {
    private val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "fixture",
        model = "fixture", systemPrompt = "", contextWindow = 200_000)

    private fun provider(block: (ProviderRequest) -> String) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit): ProviderResponse = ProviderResponse(JSONObject()
            .put("role", "assistant").put("content", block(request)).put("finish_reason", "stop"))
    }
    private fun cost(messages: JSONArray): Int = AgentTokenEstimator.messages(AssistantScreenContextProjection.project(messages))
    private fun promptLimit(request: ProviderRequest): Int = Regex("不超过 (\\d+) 字符")
        .find(request.messages.toString())!!.groupValues[1].toInt()

    @Test fun dominantFourTurnTailSkipsInfeasibleBoundariesWithoutBreakingToolExchanges() {
        for (tailPercent in listOf(86, 90, 95)) {
            val messages = JSONArray()
            for (turn in 1..6) {
                val chars = if (turn <= 2) (100 - tailPercent) * 1_000 / 2 else tailPercent * 1_000 / 4
                val call = AgentModelClient.ToolCall("read-$turn", "read_file", "{}")
                messages.put(AgentConversationCodec.userTextMessage("问题 $turn"))
                messages.put(AgentConversationCodec.assistantHistoryMessage(JSONObject().put("content", ""), listOf(call)))
                messages.put(AgentConversationCodec.toolResultMessage(call, AgentModelClient.ToolResult("证".repeat(chars))))
                messages.put(JSONObject().put("role", "assistant").put("content", "已核对 $turn"))
            }
            var calls = 0
            val result = AgentContextCompactor(config, provider { calls++; "保留已确认的证据与结论。" },
                AgentRunController(), resolveSummaryModel = { null }).compact(messages, 0, emptySet())
            assertEquals("不可行的四轮边界应在请求前跳过", 1, calls)
            assertTrue(cost(result).toLong() <= cost(messages).toLong() * 85 / 100)
            assertTrue(result.getJSONObject(0).getInt("_eta_compacted_users") > 2)
            val open = mutableSetOf<String>()
            for (index in 0 until result.length()) {
                val message = result.getJSONObject(index)
                AgentConversationCodec.parseToolCalls(message).forEach { assertTrue(open.add(it.id)) }
                if (message.optString("role") == "tool") assertTrue(open.remove(message.getString("tool_call_id")))
            }
            assertTrue(open.isEmpty())
            assertTrue(result.toString().contains("问题 6"))
        }
    }

    @Test fun fixedPrefixImagesAndInternalMetadataCannotManufactureTokenSavings() {
        var expectedCost: Int? = null
        for (url in listOf("https://example.invalid/x", "https://example.invalid/" + "x".repeat(160_000),
            "data:image/png;base64," + "A".repeat(200_000))) {
            val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "规则".repeat(6_000)))
                .put(AgentConversationCodec.userTextMessage("旧任务"))
                .put(JSONObject().put("role", "assistant").put("content", "旧".repeat(900))
                    .put("_eta_fixture_metadata", "元数据".repeat(100_000)))
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "保留最新附件"))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))))
            val before = messages.toString()
            if (expectedCost == null) expectedCost = cost(messages) else assertEquals(expectedCost, cost(messages))
            var calls = 0
            var evaluations = 0
            val failure = assertThrows(AgentModelFailure::class.java) {
                AgentContextCompactor(config, provider { calls++; error("前缀与最新附件已超目标，不能请求摘要") },
                    AgentRunController(), candidateTokens = { evaluations++; cost(it) }, resolveSummaryModel = { null })
                    .compact(messages, 1, emptySet())
            }
            assertEquals("CONTEXT_NO_REDUCTION", failure.code)
            assertEquals(0, calls)
            assertTrue("边界探索必须有界", evaluations in 2..4)
            assertEquals(before, messages.toString())
        }
    }

    @Test fun summaryPromptAndTruncationFollowTheRemainingBudget() {
        for (budget in listOf(256, 1_024)) {
            var calls = 0
            val result = AgentContextSummarizer(config, provider { request ->
                calls++
                assertEquals(budget, promptLimit(request))
                assertEquals(budget, request.outputLimit)
                "头" + "证".repeat(budget + 48) + "尾"
            }, AgentRunController(), false, summaryBudgetTokens = budget).summarizeWithUsage(
                listOf(AgentModelClient.ConversationMessage("assistant", "已完成的历史")))
            assertEquals(1, calls)
            assertTrue(result.summary.length <= budget)
            assertTrue(AgentTokenEstimator.text(result.summary) <= budget)
            assertTrue(result.summary.startsWith("头") && result.summary.endsWith("尾"))
            assertTrue(result.summary.contains("已截断"))
            assertTrue("不能靠截断把超预算响应伪装成有效缩减", result.exceededBudget)
        }
    }

    @Test fun compactorDerivesExactBudgetAfterAccountingForArchiveAndRetainedTail() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "固定规则"))
        repeat(6) { turn ->
            messages.put(AgentConversationCodec.userTextMessage("问题 $turn"))
                .put(JSONObject().put("role", "assistant").put("content", "证".repeat(2_000)))
        }
        var limit = 0
        var calls = 0
        val result = AgentContextCompactor(config, provider { request ->
            calls++
            limit = promptLimit(request)
            assertEquals(limit, request.outputLimit)
            "简明摘要"
        }, AgentRunController(), resolveSummaryModel = { null }).compact(messages, 1, emptySet())
        val skeleton = JSONArray(result.toString())
        val summary = skeleton.getJSONObject(1)
        summary.put("content", summary.getString("content").removeSuffix("简明摘要"))
        val target = minOf(cost(messages).toLong() * 85 / 100, config.contextWindow!!.toLong() * 80 / 100)
        assertEquals((target - cost(skeleton)).toInt(), limit)
        assertTrue(limit in 128 until 24_000)
        assertEquals(1, calls)
        assertTrue(cost(result) <= target)
    }

    @Test fun toolSchemasAndSessionRequestProjectionParticipateInTheSamePreflight() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "规则"))
            .put(AgentConversationCodec.userTextMessage("当前任务"))
            .put(JSONObject().put("role", "assistant").put("content", "旧".repeat(1_000)))
        val original = messages.toString()
        val tools = JSONArray().put(JSONObject().put("schema", "工具".repeat(30_000)))
        var calls = 0
        val model = provider { calls++; error("固定成本无法缩减，不应请求模型") }
        assertEquals("CONTEXT_NO_REDUCTION", assertThrows(AgentModelFailure::class.java) {
            AgentContextCompactor(config, model, AgentRunController(), mainTools = tools, resolveSummaryModel = { null })
                .compact(messages, 1, emptySet())
        }.code)
        val manual = AgentContextSession(config, JSONArray(original), 1, "manual-tools", model, AgentRunController(),
            { emptySet() }, {}, {}, mainToolsProvider = { tools })
        assertEquals(cost(messages) + AgentTokenEstimator.json(tools), manual.effectiveTokens())
        assertEquals("CONTEXT_NO_REDUCTION", assertThrows(AgentModelFailure::class.java) { manual.compact(force = true) }.code)
        val session = AgentContextSession(config, messages, 1, "projection", model, AgentRunController(),
            { emptySet() }, {}, {}, requestProjection = { candidate ->
                JSONArray(candidate.toString()).put(JSONObject().put("role", "system").put("content", "前缀".repeat(30_000)))
            })
        assertEquals("CONTEXT_NO_REDUCTION", assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }.code)
        assertTrue(session.snapshot()!!.tokenBaseline!!.compactionCooldownRemaining > 0)
        assertEquals(original, messages.toString())
        assertEquals(0, calls)
    }
}
