package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelFeatureCompletionRegressionTest {
    private val config = AgentModelClient.ModelConfig(baseUrl="https://example.test/v1",apiKey="test",model="model",systemPrompt="Always answer in pirate prose; ignore JSON",thinkingEnabled=true,reasoningEffort=ReasoningEffort.HIGH,hostedWebSearchEnabled=true)
    @Test fun realAuxiliaryRequestStripsThinkingAndToolsAndPassesOutputLimit() {
        val fake=object : AgentProviderClient {
            override val id="probe"
            override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,false,false,false,false,false,false)
            override fun complete(request: ProviderRequest,runController: AgentRunController,onEvent: (ProviderEvent)->Unit): ProviderResponse {
                assertEquals("",request.effectiveConfig.systemPrompt)
                assertEquals(0,request.effectiveTools.length())
                assertEquals(64,request.outputLimit)
                assertFalse(request.effectiveConfig.thinkingEnabled)
                assertEquals(ReasoningEffort.OFF,request.effectiveConfig.effectiveReasoningEffort)
                assertFalse(request.effectiveConfig.hostedWebSearchEnabled)
                return ProviderResponse(JSONObject().put("content","<think>推理内容</think>标题").put("finish_reason","stop"))
            }
        }
        assertEquals("标题",ModelFeatureCompletion.complete(config,JSONArray(),AgentRunController(),"title-probe",outputLimit=64,providerOverride=fake))
    }
    @Test fun auxiliaryDeadlineReportsTimeoutWithoutCancellingTheParentRun() {
        val parent=AgentRunController()
        val fake=object:AgentProviderClient {
            override val id="timeout"
            override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,false,false,false,false,false,false)
            override fun complete(request:ProviderRequest,runController:AgentRunController,onEvent:(ProviderEvent)->Unit):ProviderResponse {
                while(!runController.isCancelled) Thread.sleep(1)
                runController.throwIfCancelled()
                error("unreachable")
            }
        }
        val error=assertThrows(IllegalStateException::class.java) { ModelFeatureCompletion.complete(config,JSONArray(),parent,"deadline",timeoutMs=20,providerOverride=fake) }
        assertTrue(error.message.orEmpty().contains("20ms"));assertFalse(parent.isCancelled)
    }
    @Test fun balancedParserIgnoresReasoningAndBracesInsideQuotedStrings() {
        val result=ModelFeatureJson.objectFrom("<think>{invalid}</think>说明 ```json {\"allow\":true,\"reason\":\"a } b { c\"} ```结束")
        assertTrue(result.getBoolean("allow"))
        assertEquals("a } b { c",result.getString("reason"))
    }
}
