package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolRequirements
import org.junit.Assert.*
import org.junit.Test

class AgentToolLoadingRegressionTest {
    @Test fun everyRealToolHasExactlyOneExplicitGroup() {
        val names = AgentToolRequirements.toolNames + setOf("subagent","conversation_history","subagent_profile","eta_author_tool","eta_list_recipes")
        assertEquals(emptySet<String>(),names.filter { AgentToolGroups.classify(it)=="unclassified" }.toSet())
        assertEquals("basic",AgentToolGroups.classify("subagent"))
        assertEquals("personal",AgentToolGroups.classify("search_calendar_todos"))
        assertEquals("device",AgentToolGroups.classify("set_screen_timeout"))
        assertEquals("device_state",AgentToolGroups.classify("wifi_credentials"))
        assertEquals("unclassified",AgentToolGroups.classify("a_future_write_tool"))
    }
    @Test fun chineseSubstringsAndEnglishTermsWorkAndUnrelatedOrTermsAreExcluded() {
        val candidates = listOf(
            AgentToolSearch.Candidate("create_calendar_event","创建日历日程和会议",null,false,"calendar"),
            AgentToolSearch.Candidate("read_note","读取便签内容",null,false,"personal"),
            AgentToolSearch.Candidate("web_search","search web pages",null,false,"web"))
        assertEquals(listOf("create_calendar_event"),AgentToolSearch.search("日历",candidates).map { it.name })
        assertEquals(listOf("create_calendar_event"),AgentToolSearch.search("日历 会议",candidates).map { it.name })
        assertTrue(AgentToolSearch.search("日历 便签",candidates).isEmpty())
        assertEquals(listOf("web_search"),AgentToolSearch.search("web pages",candidates).map { it.name })
    }
}
