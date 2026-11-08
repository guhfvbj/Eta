package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.decision.*
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentToolReviewCoordinatorRegressionTest {
    private val config=AgentModelClient.ModelConfig(baseUrl="https://example.test/v1",apiKey="test",model="main",systemPrompt="")
    private val call=AgentModelClient.ToolCall("id","write_file","{\"path\":\"/tmp/file\",\"content\":\"ok\"}")
    @Before fun clear()=AgentToolReviewCoordinator.clearCache()
    @Test fun cachedReviewsAreIsolatedByHumanRequestAndConversationAndNeverCacheFailure() {
        var calls=0
        val provider=object: AgentProviderClient {
            override val id="review"
            override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,false,false,false,false,false,false)
            override fun complete(request: ProviderRequest,runController: AgentRunController,onEvent:(ProviderEvent)->Unit):ProviderResponse {
                calls++
                return ProviderResponse(JSONObject().put("content","{\"decision\":\"allow\"}").put("finish_reason","stop"))
            }
        }
        fun review(prompt:String,conversation:String)=AgentToolReviewCoordinator.review(JSONObject(),call,prompt,config,AgentRunController(),conversation=conversation,resolveModel={null},providerOverride=provider)
        assertEquals(true,review("write the file","one"))
        assertEquals(true,review("write the file","one"));assertEquals(1,calls)
        review("read only","one");review("write the file","two");assertEquals(3,calls)
    }
    @Test fun decisionEngineReusesDecisionConfigurationAndChoiceProtocol() {
        var seen=false
        val settings=JSONObject().put("review_engine","decision").put("decision_model","judge-id").put("decision_endpoint","https://judge.test/v1/systemone").put("review_timeout",20000)
        val verdict=AgentToolReviewCoordinator.review(settings,call,"write the requested file",config,AgentRunController(),resolveModel={id-> assertEquals("judge-id",id);config.copy(model="judge")},decisionOverride={cfg,_,questions->
            assertEquals("https://judge.test/v1/systemone",cfg.baseUrl);assertEquals("judge",cfg.model);assertEquals(20000,cfg.timeoutMs)
            assertTrue(questions.single() is AgentDecisionQuestion.Choice);seen=true
            AgentDecisionOutcome.Success(AgentDecisionResponse(mapOf("tool_call_review" to AgentDecisionAnswer.Choice("allow",emptyMap(),.95)),emptyList(),null,200,1))
        })
        assertTrue(seen);assertEquals(true,verdict)
    }
    @Test fun failedReviewIsNotCachedAsApprovalOrPermanentFallback() {
        var calls=0
        val provider=object: AgentProviderClient {
            override val id="failure"
            override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,false,false,false,false,false,false)
            override fun complete(request:ProviderRequest,runController:AgentRunController,onEvent:(ProviderEvent)->Unit):ProviderResponse {
                calls++;throw AgentModelFailure("HTTP_400",false,"bad request")
            }
        }
        repeat(2) { assertNull(AgentToolReviewCoordinator.review(JSONObject(),call,"write",config,AgentRunController(),resolveModel={null},providerOverride=provider)) }
        assertEquals(2,calls)
    }
    @Test fun recipeReviewIncludesRiskAndActualDefinitionAndChangesInvalidateCache() {
        var calls=0
        val provider=object:AgentProviderClient {
            override val id="effects"
            override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,false,false,false,false,false,false)
            override fun complete(request:ProviderRequest,runController:AgentRunController,onEvent:(ProviderEvent)->Unit):ProviderResponse {
                calls++
                val state=JSONObject(request.messages.getJSONObject(1).getString("content"))
                assertEquals("HIGH",state.getString("risk_level"))
                assertTrue(state.getJSONObject("effect_definition").has("steps"))
                return ProviderResponse(JSONObject().put("content","{\"decision\":\"deny\"}").put("finish_reason","stop"))
            }
        }
        val recipeCall=AgentModelClient.ToolCall("recipe","recipe_test","{}")
        repeat(2) { version ->
            val definition=JSONObject().put("steps",org.json.JSONArray().put(JSONObject().put("op","literal").put("text","version-$version")))
            assertEquals(false,AgentToolReviewCoordinator.review(JSONObject(),recipeCall,"read",config,AgentRunController(),
                assessment=AgentRiskAssessment(AgentRiskLevel.HIGH,listOf("write effect")),effectDetails=definition,resolveModel={null},providerOverride=provider))
        }
        assertEquals(2,calls)
    }
}
