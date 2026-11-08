package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentToolCallValidator
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AgentReviewRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun destructiveCommandWithoutActionAndInEveryTerminalVariantStillRequiresHuman() {
        listOf("terminal" to "", "terminal_job" to "start", "terminal_daemon" to "start").forEach { (name, action) ->
            val args = JSONObject().put("command", "rm -rf /data")
            if (action.isNotEmpty()) args.put("action", action)
            val risk = AgentRiskPolicy.assess(name, args.toString())
            assertEquals(name, AgentRiskLevel.FATAL, risk.level)
            listOf("strict", "standard", "auto", "smart").forEach { mode ->
                assertEquals("$name/$mode", AgentToolReviewPolicy.Action.CONFIRM, AgentToolReviewPolicy.decide(mode, risk.level, true))
            }
        }
    }
    @Test fun fiveModesHaveExplicitRiskMatrix() {
        for (risk in AgentRiskLevel.entries) {
            assertEquals(if(risk == AgentRiskLevel.LOW) AgentToolReviewPolicy.Action.ALLOW else AgentToolReviewPolicy.Action.DENY,
                AgentToolReviewPolicy.decide("readonly",risk))
            assertEquals(if(risk <= AgentRiskLevel.MEDIUM) AgentToolReviewPolicy.Action.ALLOW else AgentToolReviewPolicy.Action.CONFIRM,
                AgentToolReviewPolicy.decide("auto",risk))
            assertEquals(if(risk == AgentRiskLevel.FATAL) AgentToolReviewPolicy.Action.CONFIRM else AgentToolReviewPolicy.Action.ALLOW,
                AgentToolReviewPolicy.decide("smart",risk,true))
            assertEquals(if(risk == AgentRiskLevel.LOW) AgentToolReviewPolicy.Action.ALLOW else AgentToolReviewPolicy.Action.CONFIRM,
                AgentToolReviewPolicy.decide("smart",risk,null))
        }
    }
    @Test fun readOnlyCatalogAndBackgroundLogsDoNotTriggerNetworkReview() {
        listOf("conversation_history", "inspect_app", "summarize_bills", "top_memory_apps", "top_storage_apps", "app_usage_summary", "tasks_triggers", "tasks_history").forEach {
            assertEquals(it, AgentRiskLevel.LOW, AgentRiskPolicy.assess(it,"{}").level)
        }
        assertEquals(AgentRiskLevel.LOW,AgentRiskPolicy.assess("terminal_job","{\"action\":\"read\"}").level)
        assertEquals(AgentRiskLevel.LOW,AgentRiskPolicy.assess("terminal_daemon","{\"action\":\"logs\"}").level)
        assertNotEquals(AgentRiskLevel.LOW, AgentRiskPolicy.assess("get_logcat", "{}").level)
    }
    @Test fun knownWritesCannotMasqueradeAsList() {
        listOf("create_calendar_event","update_calendar_event","create_note","tasks_create","tasks_update","tasks_run","skills_manage","character_memory_write","type_text","memory_write").forEach {
            assertNotEquals(it, AgentRiskLevel.LOW, AgentRiskPolicy.assess(it,"{\"action\":\"list\"}").level)
        }
    }
    @Test fun publishedAuthorSchemaCanCreateAndUpdateWithoutReenablingRecipe() {
        val store = AgentToolRecipeStore(tmp.newFolder())
        val author = AgentSelfAuthorTools(store)
        val definition = JSONObject("""{"name":"echo_probe","description":"回显","enabled":false,"parameters":{"type":"object","properties":{}},"steps":[{"op":"literal","text":"ok"}]}""")
        val args=JSONObject().put("action","create").put("name","echo_probe").put("recipe",definition)
        val validator = AgentToolCallValidator(JSONArray().put(AgentRecipeToolCatalog.authorSchema()))
        assertNull(validator.validate(AgentModelClient.ToolCall("probe", "eta_author_tool", args.toString())))
        val created = JSONObject(author.execute("eta_author_tool", args)!!)
        assertTrue(created.toString(), created.getBoolean("ok"))
        definition.remove("enabled")
        args.put("action", "update")
        val updated = JSONObject(author.execute("eta_author_tool", args)!!)
        assertTrue(updated.toString(), updated.getBoolean("ok"))
        assertFalse(store.find("echo_probe")!!.enabled)
    }
}
