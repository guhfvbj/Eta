package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSensitiveTranscriptTest {
    @Test
    fun memoryToolArgumentsAndResultsAreAlwaysSensitive() {
        assertTrue(AgentSensitiveToolPolicy.isSensitive("memory_get"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("memory_write"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_coloros_memories"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_notification_history"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("recent_app_activity"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("get_health_summary"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_personal_orders"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("search_bills"))
        assertTrue(AgentSensitiveToolPolicy.isSensitive("mcp_server_search_deadbeef"))
    }

    @Test
    fun sensitiveResultsAreOmittedButArgumentsStayUsableAndLiveMessagesAreUnchanged() {
        val callId = "call_sensitive"
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject.NULL)
                    .put(
                        "tool_calls",
                        JSONArray().put(
                            JSONObject()
                                .put("id", callId)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject()
                                        .put("name", "set_setting")
                                        .put(
                                            "arguments",
                                            """{"namespace":"global","key":"demo","value":"敏感值"}""",
                                        ),
                                ),
                        ),
                    ),
            )
            .put(
                JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", callId)
                    .put("content", """{"ok":true,"password":"secret-value"}"""),
            )

        val encoded = AgentConversationCodec.transcript(
            messages = messages,
            startIndex = 0,
            sensitiveToolCallIds = setOf(callId),
        ).joinToString { it.content + it.toolCallsJson }

        assertTrue(encoded.contains("敏感值"))
        assertFalse(encoded.contains("secret-value"))
        assertFalse(encoded.contains("redacted"))
        assertTrue(messages.getJSONObject(1).getString("content").contains("secret-value"))
        assertTrue(messages.getJSONObject(0).toString().contains("敏感值"))
        assertTrue(encoded.contains("未写入持久会话"))
    }
    private fun assistant(name: String, args: String, id: String = "probe") = JSONObject().put("role", "assistant")
        .put("content", "").put("tool_calls", JSONArray().put(JSONObject().put("id", id).put("type", "function")
            .put("function", JSONObject().put("name", name).put("arguments", args))))

    @Test fun credentialsAreOmittedWithoutDamagingRecipeParameterSchemasOrCurrentArguments() {
        val args="""{"action":"create","recipe":{"name":"probe_tool","parameters":{"type":"object","properties":{"api_key":{"type":"string"}}},"steps":[{"headers":{"Authorization":"Bearer private-value"}}]},"api_key":"private-value"}"""
        val live=JSONArray().put(assistant("eta_author_tool",args))
        val saved=AgentConversationCodec.transcript(live,0).single()
        val stored=JSONObject(JSONArray(saved.toolCallsJson).getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals("create",stored.getString("action"))
        assertEquals("string",stored.getJSONObject("recipe").getJSONObject("parameters").getJSONObject("properties").getJSONObject("api_key").getString("type"))
        assertFalse(stored.toString().contains("private-value"))
        assertTrue(live.toString().contains("private-value"))
    }

    @Test fun legacyPlaceholderGetsActionableValidationEvenForToolsWithoutRequiredFields() {
        val validator=AgentToolCallValidator(JSONArray().put(AgentFeatureSession.schema("eta_list_recipes","list",JSONObject())))
        val failure=validator.validate(AgentModelClient.ToolCall("probe","eta_list_recipes","{\"redacted\":true}"))!!
        assertTrue(failure.contains("历史占位符"));assertTrue(failure.contains("Schema"));assertTrue(failure.contains("真实参数"))
        assertNull(validator.validate(AgentModelClient.ToolCall("probe","eta_list_recipes","{}")))
    }

    @Test fun legacyPlaceholderIsExplainedInOpenAiChatAndResponsesHistory() {
        val messages=JSONArray().put(assistant("eta_author_tool","{\"redacted\":true}"))
            .put(JSONObject().put("role","tool").put("tool_call_id","probe").put("content","omitted"))
        val projected=OpenAiRequestMessages.forChatCompletions(messages)
        assertTrue(projected.getJSONObject(0).getString("content").contains("请按工具 Schema"))
        assertTrue(OpenAiRequestMessages.responsesInstructions(messages).contains("请按工具 Schema"))
    }

    @Test fun sensitiveTranscriptPublishingCannotPolluteTheNextLiveModelRequest() {
        var round=0
        var saved=emptyList<AgentModelClient.ConversationMessage>()
        val provider=object:AgentProviderClient {
            override val id="sensitive-loop"
            override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,false,false,false,false,false,false)
            override fun complete(request:ProviderRequest,runController:AgentRunController,onEvent:(ProviderEvent)->Unit):ProviderResponse {
                if(round++==0) return ProviderResponse(assistant("mcp_sensitive_probe","{\"query\":\"日历\",\"api_key\":\"secret-in-memory\"}")
                    .put("finish_reason","tool_calls"))
                val tool=(0 until request.messages.length()).map { request.messages.getJSONObject(it) }.first { it.optString("role")=="tool" }
                assertEquals("sensitive-result",tool.getString("content"))
                val previous=(0 until request.messages.length()).map { request.messages.getJSONObject(it) }.first { it.has("tool_calls") }
                val args=JSONObject(AgentConversationCodec.parseToolCalls(previous).single().argumentsJson)
                assertEquals("日历",args.getString("query"));assertEquals("secret-in-memory",args.getString("api_key"))
                return ProviderResponse(JSONObject().put("role","assistant").put("content","done").put("finish_reason","stop"))
            }
        }
        val result=AgentModelClient.complete(
            AgentModelClient.ModelConfig(baseUrl="https://example.test/v1",apiKey="test",model="test",systemPrompt="",autoCompactionEnabled=false),
            "测试",AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("sensitive-result",sensitive=true) },
            provider=provider,additionalTools=JSONArray().put(AgentFeatureSession.schema("mcp_sensitive_probe","test",JSONObject())),onTranscript={saved=it})
        assertEquals("done",result.content)
        val persisted=saved.joinToString { it.content+it.toolCallsJson }
        assertFalse(persisted.contains("sensitive-result"));assertFalse(persisted.contains("secret-in-memory"))
        assertTrue(persisted.contains("日历"));assertFalse(persisted.contains("redacted"))
    }

}
