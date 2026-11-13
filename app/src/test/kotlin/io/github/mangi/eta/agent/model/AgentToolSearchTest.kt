package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolSearchTest {
    @Test
    fun splitsCamelSnakeAndKebabNamesAndRequiresAllOrdinaryKeywords() {
        for (name in listOf("sendText", "send_text", "send-text", "HTTPSendText")) {
            assertEquals(listOf(name), search("send text", candidate(name)))
            assertTrue(search("send missing", candidate(name)).isEmpty())
        }
    }

    @Test
    fun recognizesEightHexMcpSuffixAndTokenizesTheOriginalToolName() {
        val name = "mcp_server_sendText_a1b2c3d4"
        assertEquals(listOf(name), search("send text", candidate(name, mcp = true)))
        assertTrue(search("a1b2c3d4", candidate(name, mcp = true)).isEmpty())
        assertEquals(listOf("mcp_server_send_text_abcd"),
            search("send text", candidate("mcp_server_send_text_abcd", mcp = true)))
    }

    @Test
    fun matchesServerNameAndOriginalToolNameFromSearchHints() {
        val tool = candidate("mcp_abc_lookup_12345678", hint = "Acme Calendar findEvents", mcp = true)
        assertEquals(listOf(tool.name), search("acme calendar", tool))
        assertEquals(listOf(tool.name), search("findevents", tool))
    }

    @Test
    fun supportsMcpPrefixesAndExactSelectWithStableDeduplication() {
        val tools = listOf(candidate("mcp_one_search_12345678", mcp = true), candidate("mcp_two_read_abcdef12", mcp = true))
        assertEquals(listOf(tools[0].name), AgentToolSearch.search("mcp_one_", tools).map { it.name })
        assertEquals(listOf(tools[1].name, tools[0].name), AgentToolSearch.search(
            "select:${tools[1].name},missing,${tools[0].name.uppercase()},${tools[1].name}", tools,
        ).map { it.name })
    }

    @Test
    fun requiredTermsAreMandatoryButOtherTermsOnlyRankResults() {
        val read = candidate("read_file", description = "Read a document")
        val write = candidate("write_file", description = "Write a document")
        val hits = AgentToolSearch.search("+document read missing", listOf(write, read))
        assertEquals(listOf(read.name, write.name), hits.map { it.name })
        assertTrue(AgentToolSearch.search("+absent read", listOf(read, write)).isEmpty())
        assertEquals(listOf(read.name), AgentToolSearch.search("+read +document", listOf(read, write)).map { it.name })
    }

    @Test
    fun regexCharactersAreLiteralAndMalformedRegexNeverThrows() {
        val literal = candidate("literal", description = "Supports .* | [x] C++ (foo)")
        val ordinary = candidate("read_file", description = "Read files")
        for (query in listOf(".*", "|", "[x]", "C++", "(foo)")) {
            assertEquals(listOf(literal.name), AgentToolSearch.search(query, listOf(literal, ordinary)).map { it.name })
        }
        assertTrue(search("[", ordinary).isEmpty())
    }

    @Test
    fun clampsResultCountsForKeywordPrefixAndSelectPaths() {
        val tools = (1..30).map { candidate("mcp_server_tool${it}_12345678", mcp = true) }
        for (query in listOf("tool", "mcp_", "select:" + tools.joinToString(",") { it.name })) {
            assertEquals(5, AgentToolSearch.search(query, tools).size)
            assertEquals(20, AgentToolSearch.search(query, tools, 999).size)
            assertEquals(1, AgentToolSearch.search(query, tools, 0).size)
            assertEquals(2, AgentToolSearch.search(query, tools, 2).size)
        }
        assertTrue(AgentToolSearch.search(" ", tools).isEmpty())
        assertTrue(AgentToolSearch.search("tool", emptyList()).isEmpty())
    }

    private fun candidate(name: String, description: String = "", hint: String? = null, mcp: Boolean = false) =
        AgentToolSearch.Candidate(name, description, hint, mcp, "mcp:server")

    private fun search(query: String, candidate: AgentToolSearch.Candidate) =
        AgentToolSearch.search(query, listOf(candidate)).map { it.name }
}
