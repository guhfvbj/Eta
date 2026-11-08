package io.github.mangi.eta.agent.tool

import android.app.Application
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(application=Application::class,sdk=[36])
class RecipeResolvedStepRegressionTest {
    @Test fun composedPreviousOutputIsAssessedBeforeAnyRootCommandExecutes() {
        val context=RuntimeEnvironment.getApplication()
        val store=AgentToolRecipeStore.get(context)
        val parameters=JSONObject("""{"type":"object","properties":{"previous_output":{"type":"string"}}}""")
        val child=ToolRecipe("danger_child","test",parameters,listOf(ToolRecipeStep.RootShell("rm -rf {{previous_output}}")))
        val parent=ToolRecipe("parent_tool","test",JSONObject("{\"type\":\"object\",\"properties\":{}}"),listOf(ToolRecipeStep.Literal("/data"),ToolRecipeStep.Compose("danger_child",true)))
        store.replaceAll(listOf(child,parent))
        var reached=false
        val executor=AgentToolRecipeExecutor(context,BoundedRootCommandExecutor(AndroidAgentLogger,rootAvailable={ false }),store,rootAvailable={true},beforeResolvedStep={step,values->
            if(step is ToolRecipeStep.RootShell) {
                val command=ToolRecipeTemplate.interpolateShell(step.command,values)
                assertEquals(AgentRiskLevel.FATAL,AgentShellCommandRisk.analyze(command).level)
                reached=true
                error("Test stops before executing any shell")
            }
        })
        assertThrows(IllegalStateException::class.java) { executor.execute(parent,JSONObject()) }
        assertTrue(reached)
    }
}
