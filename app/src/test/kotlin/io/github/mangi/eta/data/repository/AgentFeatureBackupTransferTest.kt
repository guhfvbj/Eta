package io.github.mangi.eta.data.repository

import android.app.Application
import io.github.mangi.eta.agent.model.AgentFeaturePreferences
import io.github.mangi.eta.agent.tool.*
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[36])
class AgentFeatureBackupTransferTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun recipe(name: String)=ToolRecipe(name,"备份测试",JSONObject("{\"type\":\"object\",\"properties\":{}}"),listOf(ToolRecipeStep.Literal("ok")),enabled=false)
    @Test fun logicalBackupRestoresSettingsManualTitleAndMoreThan32RecipesIncludingEmptyLibrary() {
        val store=AgentToolRecipeStore.get(context)
        store.replaceAll((0 until 40).map { recipe("tool_$it") })
        AgentFeaturePreferences.set("decision_model","selected-model",context)
        AgentFeaturePreferences.protectTitle("protected",context)
        val backup=AgentFeatureBackupTransfer.snapshot(context)
        store.replaceAll(emptyList())
        context.getSharedPreferences("eta_agent_features",0).edit().clear().commit()
        AgentFeatureBackupTransfer.restore(context,backup.settings,backup.recipes)
        assertEquals(40,store.all().size)
        assertTrue(store.all().none { it.enabled })
        assertEquals("selected-model",AgentFeaturePreferences.load(context).getString("decision_model"))
        assertTrue(AgentFeaturePreferences.manualTitle("protected",context))
        AgentFeatureBackupTransfer.restore(context,null,emptyList())
        assertTrue(store.all().isEmpty())
    }
    @Test fun invalidGraphCannotReplaceCurrentRecipesAndLegacyAbsentFieldsPreserveData() {
        val store=AgentToolRecipeStore.get(context);store.replaceAll(listOf(recipe("original")))
        val bad="""{"name":"broken","description":"bad","parameters":{"type":"object","properties":{}},"steps":[{"op":"compose","recipe":"missing"}]}"""
        assertThrows(Exception::class.java) { AgentFeatureBackupTransfer.restore(context,null,listOf(bad)) }
        assertEquals(listOf("original"),store.all().map { it.name })
        AgentFeatureBackupTransfer.restore(context,null,null)
        assertEquals(listOf("original"),store.all().map { it.name })
    }
    @Test fun stagedLogicalDocumentRetainsFeatureFields() {
        kotlinx.coroutines.runBlocking {
            val raw="""{"format":"eta-backup","schemaVersion":5,"exportedAt":1,"agentFeaturesJson":"{\"settings\":\"{}\"}","toolRecipesJson":[],"conversations":[],"messages":[],"contextCheckpoints":[]}"""
            EtaBackupJsonStreams.stage(context,raw.byteInputStream()).use { staged ->
                assertNotNull(staged.header.agentFeaturesJson)
                assertEquals(emptyList<String>(),staged.header.toolRecipesJson)
            }
        }
    }
}
