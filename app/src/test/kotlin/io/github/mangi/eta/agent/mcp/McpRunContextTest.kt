package io.github.mangi.eta.agent.mcp

import android.app.Application
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
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
@Config(sdk = [36], application = Application::class)
class McpRunContextTest {
    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("eta.db")
        McpServerRepository.init(context)
        runBlocking {
            McpServerRepository.servers().forEach { McpServerRepository.delete(it.id) }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            runCatching { McpServerRepository.servers().forEach { McpServerRepository.delete(it.id) } }
        }
        EtaDatabase.closeForTests()
    }

    @Test
    fun loadRetainsAllActiveToolsAcrossFourServersPastTheFullMountLimit() = runBlocking {
        val servers = storeLargeCatalog()
        McpServerRepository.add(servers.first().copy(id = "disabled", enabled = false), bearerToken = "")

        val snapshot = McpRunSnapshot.load()
        val expected = servers.flatMap { server -> server.activeTools.map { server.id to it.name } }

        assertEquals(96, snapshot.tools.size)
        assertEquals(expected, snapshot.tools.map { it.server.id to it.definition.name })
        assertEquals(96, snapshot.tools.map { it.modelName }.toSet().size)
        assertEquals("three", snapshot.tools[63].server.id)
        assertEquals("three", snapshot.tools[64].server.id)
        assertEquals("four", snapshot.tools[72].server.id)
        val groups = snapshot.deferredGroups()
        assertEquals(servers.map { mcpGroupId(it.id) }, groups.map { it.id })
        groups.forEach { group ->
            assertEquals(24, group.toolNames.size)
            assertEquals(snapshot.tools.filter { mcpGroupId(it.server.id) == group.id }
                .map { it.modelName }.toSet(), group.toolNames)
        }
        val candidates = snapshot.searchCandidates(groups.mapTo(linkedSetOf()) { it.id })
        assertEquals(snapshot.tools.map { it.modelName }, candidates.map { it.name })
        val lateTools = listOf(snapshot.tools[64], snapshot.tools[71], snapshot.tools[72], snapshot.tools.last())
        lateTools.forEach { tool -> assertEquals(tool, snapshot.resolve(tool.modelName)) }
        assertEquals(setOf("mcp:three", "mcp:four"), snapshot.openedGroupIdsFrom(lateTools.map { it.modelName }))
        assertFalse(snapshot.tools.any { it.definition.name == "disabled_tool" || it.server.id == "disabled" })
    }

    @Test
    fun onlyUpfrontMountingCapsAppendedMcpToolsAt64() = runBlocking {
        storeLargeCatalog()
        val snapshot = McpRunSnapshot.load()
        val allNames = snapshot.tools.map { it.modelName }
        fun JSONArray.names() = (0 until length()).map { getJSONObject(it).getJSONObject("function").getString("name") }

        val upfront = JSONArray().also(snapshot::appendModelTools)
        assertEquals(64, upfront.length())
        assertEquals(allNames.take(64), upfront.names())
        // Existing local schemas do not count against the MCP-only mounting cap.
        val withLocal = JSONArray().put(JSONObject().put("function", JSONObject().put("name", "local")))
        snapshot.appendModelTools(withLocal)
        assertEquals(listOf("local") + allNames.take(64), withLocal.names())

        val permitted = JSONArray().also { snapshot.appendTools(it, openedGroupIds = null) }
        assertEquals(96, permitted.length())
        assertEquals(allNames, permitted.names())
        val opened = snapshot.deferredGroups().mapTo(linkedSetOf()) { it.id }
        val progressive = JSONArray().also { snapshot.appendTools(it, opened) }
        assertEquals(96, progressive.length())
        assertEquals(allNames, progressive.names())
        val lateOnly = JSONArray().also { snapshot.appendTools(it, setOf("mcp:three", "mcp:four")) }
        assertEquals(allNames.drop(48), lateOnly.names())
        val allowed = allNames.drop(64).toSet()
        for (groups in listOf(null, opened)) {
            val filtered = JSONArray().also { snapshot.appendTools(it, groups) { name -> name in allowed } }
            assertEquals(allNames.drop(64), filtered.names())
        }
        assertEquals(0, JSONArray().also { snapshot.appendTools(it, emptySet()) }.length())
    }

    @Test
    fun executorCallsLoadedToolsPast64OnStraddlingAndFourthServers() = runBlocking {
        val requestedMethods = ConcurrentLinkedQueue<String>()
        val requestedNames = ConcurrentLinkedQueue<String>()
        val server = localServer { exchange ->
            val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            requestedMethods.add(request.getString("method"))
            val name = request.getJSONObject("params").getString("name")
            requestedNames.add(name)
            exchange.respond(JSONObject().put("jsonrpc", "2.0").put("id", request.get("id"))
                .put("result", JSONObject().put("resultType", "complete").put("content", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", "executed $name")))).toString())
        }
        try {
            storeLargeCatalog(server.url())
            val snapshot = McpRunSnapshot.load()
            assertEquals(96, snapshot.tools.size)
            val targets = listOf(snapshot.tools[64], snapshot.tools[71], snapshot.tools[72], snapshot.tools.last())
            val executor = McpToolExecutor(snapshot)
            try {
                targets.forEachIndexed { index, tool ->
                    assertTrue(executor.contains(tool.modelName))
                    val result = executor.execute(AgentModelClient.ToolCall("late-$index", tool.modelName, "{}"))
                    val payload = JSONObject(result.content)
                    assertTrue(payload.getBoolean("ok"))
                    assertTrue(result.sensitive)
                    assertEquals(tool.server.name, payload.getString("server"))
                    assertEquals(tool.definition.name, payload.getString("tool"))
                    assertEquals("executed ${tool.definition.name}", payload.getJSONArray("content").getString(0))
                }
                assertEquals(List(targets.size) { "tools/call" }, requestedMethods.toList())
                assertEquals(targets.map { it.definition.name }, requestedNames.toList())
            } finally {
                executor.close()
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun enabledToolIsActiveRegardlessOfSchemaKeywords() {
        val definition = McpToolDefinition(
            name = "create_task",
            inputSchemaJson = """{"type":"object","anyOf":[]}""",
        )
        val server = McpServerSetting(
            id = "server",
            name = "Server",
            url = "https://example.com/mcp",
            tools = listOf(definition),
            enabledToolNames = setOf(definition.name),
        )

        assertEquals(listOf(definition), server.activeTools)
    }

    @Test
    fun snapshotUsesFrozenTokenAndReportsTruncatedOrUnsupportedContent() {
        val authorization = AtomicReference<String>()
        val server = localServer { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.respond(
                """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[{"type":"resource_link","name":"Docs","uri":"https://example.com/docs"},{"type":"audio","mimeType":"audio/wav","data":"AA=="},{"type":"text","text":"${"x".repeat(70_000)}"}]}}""",
            )
        }
        try {
            val definition = McpToolDefinition(
                name = "search",
                inputSchemaJson = """{"type":"object"}""",
            )
            val setting = McpServerSetting(
                id = "server",
                name = "Server",
                url = server.url(),
                lastProtocolVersion = McpProtocolMode.LATEST,
                tools = listOf(definition),
                enabledToolNames = setOf(definition.name),
            )
            val executor = McpToolExecutor(
                McpRunSnapshot(
                    listOf(McpRunTool("mcp_server_search", setting, definition, "run-token"))
                )
            )

            val result = executor.execute(
                AgentModelClient.ToolCall("call-1", "mcp_server_search", "{}")
            )
            executor.close()
            val payload = JSONObject(result.content)

            assertEquals("Bearer run-token", authorization.get())
            assertTrue(result.sensitive)
            assertTrue(payload.getBoolean("truncated"))
            assertEquals(1, payload.getInt("omitted_items"))
            assertTrue(payload.getJSONArray("content").getString(0).contains("https://example.com/docs"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun closedExecutorDoesNotStartFirstRemoteCall() {
        val requests = AtomicInteger()
        val server = localServer { exchange ->
            requests.incrementAndGet()
            exchange.respond("""{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","content":[]}}""")
        }
        try {
            val definition = McpToolDefinition("search", inputSchemaJson = """{"type":"object"}""")
            val setting = McpServerSetting(
                id = "server",
                name = "Server",
                url = server.url(),
                lastProtocolVersion = McpProtocolMode.LATEST,
            )
            val executor = McpToolExecutor(
                McpRunSnapshot(
                    listOf(McpRunTool("mcp_server_search", setting, definition, null))
                )
            )
            executor.close()

            val result = executor.execute(
                AgentModelClient.ToolCall("call-1", "mcp_server_search", "{}")
            )

            assertEquals("MCP_EXECUTOR_CLOSED", JSONObject(result.content).getString("code"))
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    private suspend fun storeLargeCatalog(url: String = "https://example.invalid/mcp"): List<McpServerSetting> =
        listOf("one", "two", "three", "four").map { id ->
            // Server three straddles tool 64; server four starts at tool 73.
            val active = (1..24).map { index ->
                McpToolDefinition("read_${id}_$index", inputSchemaJson = """{"type":"object"}""")
            }
            McpServerRepository.add(McpServerSetting(
                id = id,
                name = "Server $id",
                url = url,
                tools = active + McpToolDefinition("disabled_tool", inputSchemaJson = """{"type":"object"}"""),
                enabledToolNames = active.mapTo(linkedSetOf()) { it.name },
                lastProtocolVersion = McpProtocolMode.LATEST,
                toolsExpireAt = Long.MAX_VALUE,
            ), bearerToken = "")
        }

    private fun localServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp", handler)
            start()
        }

    private fun HttpServer.url(): String = "http://127.0.0.1:${address.port}/mcp"

    private fun HttpExchange.respond(body: String) {
        responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
