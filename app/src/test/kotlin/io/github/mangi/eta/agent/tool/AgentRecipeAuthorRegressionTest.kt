package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.model.AgentToolCallValidator
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AgentRecipeAuthorRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
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
