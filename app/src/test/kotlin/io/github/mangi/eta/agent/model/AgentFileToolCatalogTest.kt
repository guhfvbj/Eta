package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.terminal.FileToolReadLimits
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentFileToolCatalogTest {
    @Test fun readSchemaAdvertisesActualThroughputAndSafeContinuationContract() {
        val tools = JSONArray().also(AgentFileToolCatalog::appendTo)
        val read = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == "read_file" }
        val properties = read.getJSONObject("parameters").getJSONObject("properties")
        assertEquals(FileToolReadLimits.MAX_BYTES, properties.getJSONObject("max_bytes").getInt("default"))
        assertEquals(FileToolReadLimits.MAX_LINES, properties.getJSONObject("max_lines").getInt("default"))
        assertEquals(FileToolReadLimits.MAX_LINES, properties.getJSONObject("max_lines").getInt("maximum"))
        for (hint in listOf("total_lines", "continuation", "scan_limit", "复用已读结果", "51200")) {
            assertTrue(hint, read.getString("description").contains(hint))
        }
    }

    @Test fun fileToolDescriptionsReflectAvailableIdentity() {
        val tools = AgentToolCatalog.build(true, false, capabilities = AgentToolCapabilities(rootAvailable = false))
        val fileTools = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function") }
            .filter { it.getString("name") in AgentFileToolCatalog.names }
        assertEquals(AgentFileToolCatalog.names, fileTools.map { it.getString("name") }.toSet())
        fileTools.forEach { tool ->
            val identities = tool.getJSONObject("parameters").getJSONObject("properties")
                .getJSONObject("identity").getJSONArray("enum")
            assertEquals(1, identities.length())
            assertEquals("user", identities.getString(0))
        }
    }
}
