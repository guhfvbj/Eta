package io.github.mangi.eta.agent.openviking

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentOpenVikingToolCatalog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpenVikingToolsPermissionTest {
    @Test fun roleplayDeniesEveryToolBeforeLookingUpSettingsSecretsOrMakingRequests() {
        OpenVikingTools(RuntimeEnvironment.getApplication()) { false }.use { tools ->
            for (name in AgentOpenVikingToolCatalog.names) {
                val result = tools.execute(AgentModelClient.ToolCall(name, name, "{}"))
                assertTrue(result.sensitive)
                assertEquals("OPENVIKING_ROLEPLAY_BLOCKED", JSONObject(result.content).getString("code"))
            }
        }
    }
    @Test fun closedToolsReturnAStructuredErrorRatherThanThrowing() {
        val tools = OpenVikingTools(RuntimeEnvironment.getApplication())
        tools.close()
        assertEquals("CANCELLED", JSONObject(tools.execute(AgentModelClient.ToolCall("id", "ov_read", "{}")).content).getString("code"))
    }
}
