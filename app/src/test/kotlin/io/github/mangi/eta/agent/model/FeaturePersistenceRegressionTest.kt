package io.github.mangi.eta.agent.model

import android.app.Application
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[36])
class FeaturePersistenceRegressionTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Test fun editingFirstMessageCanRegenerateGeneratedTitleButCannotUnlockManualTitle() {
        val prefs=context.getSharedPreferences("eta_agent_features",0)
        prefs.edit().putBoolean("generated_title_auto",true).putBoolean("generated_title_manual",true).commit()
        AgentFeaturePreferences.protectTitle("manual",context)
        assertTrue(AgentFeaturePreferences.resetGeneratedTitleForEdit("auto",context))
        assertFalse(prefs.getBoolean("generated_title_auto",false))
        assertFalse(AgentFeaturePreferences.resetGeneratedTitleForEdit("manual",context))
        assertTrue(prefs.getBoolean("generated_title_manual",false))
    }
    @Test fun oldHiddenSwitchesAreRemovedAndCustomTitleSelectionIsRetained() {
        val prefs=context.getSharedPreferences("eta_agent_features",0)
        prefs.edit().clear().putString("settings","""{"loading":"tool","group_device":false,"disabled_tools":"subagent","title_model":"saved-title-model","decision_timeout":15000}""").commit()
        val settings=AgentFeaturePreferences.load(context)
        assertEquals("search",settings.getString("loading"))
        assertFalse(settings.has("group_device"));assertFalse(settings.has("disabled_tools"))
        assertTrue(settings.getBoolean("title_custom"));assertEquals("saved-title-model",settings.getString("title_custom_model_id"))
        assertEquals(10000,settings.getInt("decision_timeout"))
    }
    @Test fun actualResumeButtonPromptReusesDecisionButNewQuestionDoesNot() {
        val history = listOf(AgentModelClient.ConversationMessage("tool", "completed result"))
        val prompt = context.getString(io.github.mangi.eta.R.string.agent_run_resume_prompt)
        assertTrue(io.github.mangi.eta.agent.decision.AgentDecisionContinuation.detect(history, prompt))
        assertFalse(io.github.mangi.eta.agent.decision.AgentDecisionContinuation.detect(history, "新问题：查一下日历"))
        assertFalse(io.github.mangi.eta.agent.decision.AgentDecisionContinuation.detect(
            listOf(AgentModelClient.ConversationMessage("assistant", "finished")), prompt))
    }
    @Test fun nonTextGenerationModelsAreExcludedFromAuxiliarySelection() {
        fun model(id:String,outputs:List<String>)=Model(id,id,id,outputModalities=outputs)
        assertTrue(AgentFeaturePreferences.isTextModel(model("text-model",listOf("text"))))
        assertFalse(AgentFeaturePreferences.isTextModel(model("image-model",listOf("text","image"))))
        assertFalse(AgentFeaturePreferences.isTextModel(model("tts-model",listOf("text"))))
        assertFalse(AgentFeaturePreferences.isTextModel(model("video-model",listOf("video"))))
        assertFalse(AgentFeaturePreferences.isTextModel(model("gemini-pro-image-preview",listOf("text"))))
    }
}
