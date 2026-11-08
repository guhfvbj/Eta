package io.github.mangi.eta.agent.decision

import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentDecisionCoordinatorRegressionTest {
    private val config=AgentModelClient.ModelConfig(baseUrl="https://example.test/v1",apiKey="test",model="primary",systemPrompt="")
    private val groups=listOf(AgentToolGroups.WEB_GROUP,AgentToolGroups.CALENDAR_GROUP)
    private val settings get()=JSONObject().put("decision_enabled",true).put("decision_shadow",false).put("decision_model","primary-id").put("decision_protocol","llm").put("decision_mode","control")
    @Before fun clear()=AgentDecisionRouter.clear()
    private fun evaluation(selected: Set<String> = setOf("web"),needs: Double=1.0) = AgentDecisionEvaluator.AgentDecisionEvaluation(
        "primary-id","https://example.test/v1/systemone",null,null,null,"",200,0,"",12,needs,selected,2,10,5,100,1,.95)
    private fun request(input: String="search")=AgentRuntimeWire.RunRequest("run",input,config,emptyList(),modelSessionId="conversation")
    @Test fun explicitMissingModelIsDiagnosedFailOpenRatherThanFakeFollowCurrent() {
        val plan=AgentDecisionRouter.route(request(),config,DecisionTraceLogger,groups,settings.put("decision_model",""),AgentRunController(),resolveModel={ error("must not look up empty id") })!!
        assertEquals("missing_model",plan.failureReason)
        assertEquals(setOf("web","calendar"),plan.allowedGroups)
        assertEquals(1,AgentDecisionTelemetry.globalSnapshot().degraded)
    }
    @Test fun controlNarrowsWhileAdviceOnlyRecordsAndShadowCannotWriteStickyState() {
        val control=AgentDecisionRouter.route(request(),config,DecisionTraceLogger,groups,settings,AgentRunController(),resolveModel={ config },evaluateOverride={_,_,_,_->evaluation()})!!
        assertEquals(setOf("web"),control.allowedGroups)
        AgentDecisionRouter.clear()
        val advice=AgentDecisionRouter.route(request(),config,DecisionTraceLogger,groups,settings.put("decision_mode","advise"),AgentRunController(),resolveModel={ config },evaluateOverride={_,_,_,_->evaluation()})!!
        assertEquals(setOf("web","calendar"),advice.allowedGroups)
        assertTrue(advice.openedGroups.isEmpty())
        AgentDecisionRouter.clear()
        val shadow=AgentDecisionRouter.route(request(),config,DecisionTraceLogger,groups,settings.put("decision_shadow",true),AgentRunController(),observeOnly=true,resolveModel={ config },evaluateOverride={_,_,_,_->evaluation()})!!
        assertEquals(setOf("web","calendar"),shadow.allowedGroups)
        assertTrue(shadow.promptSectionsDisabled.isEmpty())
        assertTrue(AgentDecisionStickyStore.current("conversation",setOf("web","calendar")).isEmpty())
    }
    @Test fun pureChatDisablesExactlyTheOptionalPromptSections() {
        val plan=AgentDecisionRouter.route(request("hello"),config,DecisionTraceLogger,groups,settings,AgentRunController(),resolveModel={ config },evaluateOverride={_,_,_,_->evaluation(emptySet(),0.0)})!!
        assertTrue(plan.chatOnly)
        assertEquals(setOf("skill_index","memory","group_index","delegation_rule"),plan.promptSectionsDisabled.toSet())
    }
    @Test fun cacheNeverReusesDifferentHistoryAndContinuationMustHaveSystemPrompt() {
        var calls=0
        repeat(3) {
            AgentDecisionRouter.route(request(),config,DecisionTraceLogger,groups,settings,AgentRunController(),resolveModel={ config },evaluateOverride={_,_,_,_-> calls++;evaluation() })
        }
        // First decision populates sticky; second uses that different cache key; third hits.
        assertEquals(2,calls)
        val changed=request().copy(history=listOf(AgentModelClient.ConversationMessage("user","new context")))
        AgentDecisionRouter.route(changed,config,DecisionTraceLogger,groups,settings,AgentRunController(),resolveModel={ config },evaluateOverride={_,_,_,_->calls++;evaluation()})
        assertEquals(3,calls)
        assertFalse(AgentDecisionContinuation.detect(listOf(AgentModelClient.ConversationMessage("tool","raw output")),"a new question"))
        assertTrue(AgentDecisionContinuation.detect(listOf(AgentModelClient.ConversationMessage("tool","raw output")),""))
    }
    @Test fun evaluatorFiltersToolOutputAndPreservesPrimaryOnUpgradeFailure() {
        var calls=0
        val request=request().copy(history=listOf(AgentModelClient.ConversationMessage("user","original human"),AgentModelClient.ConversationMessage("tool","untrusted raw output"),AgentModelClient.ConversationMessage("assistant","final reply")))
        val result=AgentDecisionEvaluator.evaluate(request,"primary-id",config.baseUrl,config.apiKey,3000,3,"",groups,emptySet(),
            escalation=AgentDecisionEscalation.Config("strong-id",config.baseUrl,"test",60,modelName="strong"),modelName="primary",runtimeConfig=config,protocol="llm",
            requestOverride={state,questions,model,timeout ->
                assertEquals(if(model=="strong") 6000 else 3000,timeout)
                assertFalse(state.toString().contains("untrusted raw output"))
                assertTrue(state.toString().contains("original human"))
                assertEquals(listOf("needs_tools","tool_groups"),questions.map { it.id })
                calls++
                if(model=="strong") AgentDecisionOutcome.Failure("timeout") else AgentDecisionOutcome.Success(AgentDecisionResponse(mapOf(
                    "needs_tools" to AgentDecisionAnswer.Noul(1.0),"tool_groups" to AgentDecisionAnswer.MultiChoice(setOf("calendar"),emptyMap(),.9)),emptyList(),null,200,5))
            })
        assertEquals(2,calls)
        assertTrue(result.succeeded)
        assertEquals(setOf("calendar"),result.selectedGroups)
        assertTrue(result.escalated)
        assertEquals("timeout",result.escalationFailure)
    }
    @Test fun ordinaryLlmCanReuseLocalProviderButCustomEndpointAndSystemOneRemainHttpsOnly() {
        val local=config.copy(baseUrl="http://127.0.0.1:8080/v1")
        assertEquals("http://127.0.0.1:8080/v1/chat/completions",AgentDecisionEndpoint.requestUrl(local,"","llm"))
        assertNull(AgentDecisionEndpoint.requestUrl(local,"http://127.0.0.1:8080/v1","llm"))
        assertNull(AgentDecisionEndpoint.requestUrl(local,"","systemone"))
        var called=false
        val result=AgentDecisionEvaluator.evaluate(request(),"local",local.baseUrl,"test",3000,0,"",groups,emptySet(),runtimeConfig=local,protocol="llm",requestOverride={_,_,_,_->
            called=true
            AgentDecisionOutcome.Success(AgentDecisionResponse(mapOf("needs_tools" to AgentDecisionAnswer.Noul(0.0),"tool_groups" to AgentDecisionAnswer.MultiChoice(emptySet(),emptyMap(),.9)),emptyList(),null,200,1))
        })
        assertTrue(called);assertTrue(result.succeeded)
        assertEquals("http://127.0.0.1:8080/v1/chat/completions",result.endpoint)
    }
    @Test fun realLlmNormalizesContinuationAndReportsHttpErrorsWithoutProviderPrompt() {
        val server=com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1",0),0)
        val requestBody=java.util.concurrent.atomic.AtomicReference<String>()
        val key="test-only-credential"
        val calls=java.util.concurrent.atomic.AtomicInteger()
        server.createContext("/v1/chat/completions") { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            val first=calls.incrementAndGet()==1
            val body=if(first) {
                val decision=JSONObject().put("needs_tools",false).put("groups",org.json.JSONArray(listOf("basic","calendar","typo"))).put("confidence",0.9)
                val chunk=JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("index",0)
                    .put("delta",JSONObject().put("content",decision.toString())).put("finish_reason","stop")))
                "data: $chunk\n\ndata: [DONE]\n\n"
            } else JSONObject().put("error",JSONObject().put("message","probe-rejected $key")).toString()
            val bytes=body.toByteArray()
            exchange.responseHeaders.add("Content-Type",if(first) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(if(first) 200 else 400,bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val local=config.copy(baseUrl="http://127.0.0.1:${server.address.port}/v1",apiKey=key,systemPrompt="provider-specific prose")
            val routed=AgentDecisionEvaluator.evaluate(request(),"primary-id",local.baseUrl,key,3000,0,"",groups,emptySet(),
                modelName="primary",runtimeConfig=local,protocol="llm")
            assertTrue(routed.detail,routed.succeeded)
            assertEquals(setOf("calendar"),routed.selectedGroups)
            assertEquals(1.0,routed.needsTools,0.0)
            val result=AgentDecisionEvaluator.evaluate(request(),"primary-id",local.baseUrl,key,3000,0,"",groups,emptySet(),
                modelName="primary",runtimeConfig=local,protocol="llm")
            assertFalse(result.succeeded)
            assertEquals(400,result.httpStatus)
            assertTrue(result.detail,result.detail.contains("HTTP 400"))
            assertFalse(result.detail.contains(key))
            assertFalse(requestBody.get().contains("provider-specific prose"))
        } finally { server.stop(0) }
    }

}
