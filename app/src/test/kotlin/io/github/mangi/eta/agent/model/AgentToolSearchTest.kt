package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具级搜索（移植自 ZCode 的 ToolSearch 本地投影）：
 * 分词（camelCase/snake_case/MCP 名）、select: 精确选择、+term 必含词与评分排序，
 * 目录与 next-turn 约束由 AgentFeatureSessionTest 验证。
 */
class AgentToolSearchTest {
    private fun candidate(
        name: String,
        description: String? = null,
        mcp: Boolean = false,
        groupId: String = "g",
    ) = AgentToolSearch.Candidate(name, description, null, mcp, groupId)

    @Test fun keywordMatchesCamelCaseTokens() {
        val hits = AgentToolSearch.search(
            "send text",
            listOf(
                candidate("sendTextMessage", "Send a text message to a contact"),
                candidate("observeScreen", "Read the current screen"),
            ),
        )
        assertEquals(listOf("sendTextMessage"), hits.map { it.name })
    }

    @Test fun snakeCaseTokensMatch() {
        val hits = AgentToolSearch.search(
            "terminal run",
            listOf(
                candidate("run_command", "Run a shell command in a terminal session"),
                candidate("read_file", "Read a file"),
            ),
        )
        assertEquals(listOf("run_command"), hits.map { it.name })
    }

    @Test fun mcpNameTokensScoreServerAndTool() {
        val hits = AgentToolSearch.search(
            "browser navigate",
            listOf(
                candidate("mcp_webtools_browser_navigate_ab12", "Navigate the browser", mcp = true, groupId = "mcp:s1"),
                candidate("mcp_webtools_notes_add_cd34", "Add a note", mcp = true, groupId = "mcp:s1"),
            ),
        )
        assertEquals(listOf("mcp_webtools_browser_navigate_ab12"), hits.map { it.name })
    }

    @Test fun mcpPrefixQueryReturnsPrefixMatches() {
        val hits = AgentToolSearch.search(
            "mcp_webtools_",
            listOf(
                candidate("mcp_webtools_browser_navigate_ab12", null, mcp = true, groupId = "mcp:s1"),
                candidate("read_file", null),
            ),
        )
        assertEquals(listOf("mcp_webtools_browser_navigate_ab12"), hits.map { it.name })
    }

    @Test fun selectPicksExactNames() {
        val hits = AgentToolSearch.search(
            "select:read_file,run_command",
            listOf(
                candidate("read_file"),
                candidate("run_command"),
                candidate("observe_screen"),
            ),
        )
        assertEquals(listOf("read_file", "run_command"), hits.map { it.name })
    }

    @Test fun requiredTermFiltersNonMatches() {
        val hits = AgentToolSearch.search(
            "+slack send",
            listOf(
                candidate("send_message", "Send a slack message to a channel"),
                candidate("send_message_sms", "Send an SMS"),
            ),
        )
        assertEquals(listOf("send_message"), hits.map { it.name })
    }

    @Test fun descriptionTermsRankCandidates() {
        val hits = AgentToolSearch.search(
            "calendar",
            listOf(
                candidate("create_event", "Create a calendar event"),
                candidate("read_file", "Read a file"),
            ),
        )
        assertEquals(listOf("create_event"), hits.map { it.name })
    }

    @Test fun noMatchReturnsEmpty() {
        val hits = AgentToolSearch.search(
            "quantum",
            listOf(candidate("read_file", "Read a file")),
        )
        assertTrue(hits.isEmpty())
    }

    @Test fun maxResultsBoundsOutput() {
        val candidates = (1..10).map { index -> candidate("tool_$index", "tool number $index for testing") }
        val hits = AgentToolSearch.search("tool", candidates, maxResults = 3)
        assertEquals(3, hits.size)
    }

}
