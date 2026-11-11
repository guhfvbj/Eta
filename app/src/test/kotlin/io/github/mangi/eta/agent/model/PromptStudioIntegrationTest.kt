package io.github.mangi.eta.agent.model

import android.app.Application
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.prompt.PromptCustomization
import io.github.mangi.eta.agent.prompt.PromptCustomizationMode
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class PromptStudioIntegrationTest {
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test-model",
        systemPrompt = "Provider identity", terminalTools = true, browserTools = true,
    )

    @Before
    fun setUp() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        Prefs.localAgentPreferences()!!.edit().clear().commit()
    }

    @After
    fun tearDown() {
        Prefs.localAgentPreferences()!!.edit().clear().commit()
    }

    @Test
    fun disabledCustomizationLeavesTheOfficialPromptByteForByteUnchanged() {
        val baseline = system(PromptCustomization())
        val disabled = system(PromptCustomization(
            enabled = false, mode = PromptCustomizationMode.REPLACE,
            baseSystemPrompt = "Ignored identity", personaName = "Ignored name",
            personaStyle = "Ignored style", appendInstructions = "Ignored instructions",
        ))
        assertEquals(baseline.toString(), disabled.toString())
    }

    @Test
    fun customizationAddsItsSegmentsAndKeepsEveryOfficialRuntimeRule() {
        val baseline = system(PromptCustomization()).contents()
        val customized = system(PromptCustomization(
            enabled = true, mode = PromptCustomizationMode.REPLACE,
            baseSystemPrompt = "Custom identity", personaName = "Custom name",
            personaStyle = "Custom style", appendInstructions = "Custom instructions",
        )).contents()
        assertTrue(customized.first().startsWith("<custom_identity"))
        assertTrue(customized.any { it.startsWith("<custom_persona") && it.contains("Custom name") })
        assertTrue(customized.last().startsWith("<custom_instructions"))
        assertFalse(customized.contains("Provider identity"))
        assertEquals(baseline.drop(1), customized.filterNot { it.startsWith("<custom_") })
        assertTrue(customized.any { it.contains("智能画布分流") && it.contains("present_to_user") })
    }

    @Test
    fun directoryIntersectsExistingDisabledToolsAndPolicyIncludingMcpTools() {
        Prefs.localAgentPreferences()!!.edit()
            .putString(Prefs.Keys.AGENT_DISABLED_TOOLS_JSON, "[\"tasks_run\"]").commit()
        val policy = AgentToolPolicy(ToolPolicyMode.ALLOW_LIST, setOf("tasks_list", "tasks_run", "mcp_allowed"))
        val additional = JSONArray().put(schema("mcp_allowed")).put(schema("mcp_denied"))
        assertEquals(setOf("tasks_list", "mcp_allowed"), directory(policy, additional).names())
        assertEquals(
            setOf("tasks_list", "tasks_run", "mcp_allowed"),
            directory(policy, additional, disabledTools = emptySet()).names(),
        )
        assertEquals(0, directory(policy, additional, rewriteReply = true).length())
    }

    @Test
    fun roleplayDirectoryDoesNotExposeTaskManagement() {
        val tools = directory(AgentToolPolicy(), JSONArray(), memoryWritable = false).names()
        assertFalse(tools.any { it.startsWith("tasks_") })
    }

    @Test
    fun manualTitlesStayProtectedAndDeletionClearsTheirFeatureState() {
        val context = RuntimeEnvironment.getApplication()
        AgentFeaturePreferences.protectTitle("conversation", context)
        context.getSharedPreferences("eta_agent_features", 0).edit()
            .putBoolean("generated_title_conversation", true).commit()
        assertFalse(AgentFeaturePreferences.resetGeneratedTitleForEdit("conversation", context))
        AgentFeaturePreferences.clearConversation("conversation", context)
        assertFalse(AgentFeaturePreferences.manualTitle("conversation", context))
        assertFalse(context.getSharedPreferences("eta_agent_features", 0).contains("generated_title_conversation"))
    }

    private fun system(customization: PromptCustomization) = AgentPromptBuilder.buildSystemMessages(
        config, SkillContext.EMPTY, AgentMemoryContext.DISABLED, rootAvailable = true,
        customization = customization,
    )

    private fun directory(
        policy: AgentToolPolicy, additional: JSONArray, disabledTools: Set<String> = Prefs.disabledTools(),
        memoryWritable: Boolean = true, rewriteReply: Boolean = false,
    ) = AgentToolDirectory.build(
        terminalTools = true, browserTools = true, deviceDirectTools = true,
        deviceSensitiveReadTools = true, deviceSensitiveActionTools = true,
        skillGitHubDiscovery = true, skillGitHubInstall = true, memoryTools = true,
        memoryWritable = memoryWritable, capabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch = true, policy = policy, disabledTools = disabledTools,
        additionalTools = additional, rewriteReply = rewriteReply,
    )

    private fun schema(name: String) = JSONObject().put("type", "function").put(
        "function", JSONObject().put("name", name).put("parameters", JSONObject().put("type", "object")),
    )
    private fun JSONArray.names() = (0 until length()).map {
        getJSONObject(it).getJSONObject("function").getString("name")
    }.toSet()
    private fun JSONArray.contents() = (0 until length()).map { getJSONObject(it).getString("content") }
}
