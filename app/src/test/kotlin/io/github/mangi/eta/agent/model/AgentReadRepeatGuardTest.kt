package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentReadRepeatGuardTest {
    private fun result(path: String = "file.kt", revision: String = "r1", offset: Int = 0,
        content: String = "CODE") = AgentModelClient.ToolResult(JSONObject()
        .put("ok", true).put("path", path).put("revision", revision)
        .put("environment", "linux").put("identity", "user")
        .put("offset_bytes", offset).put("next_offset_bytes", offset + content.length)
        .put("stop_reason", "eof").put("content", content).toString(),
        sensitive = true, command = "trace")

    @Test fun unchangedReadsAcrossInterleavedFilesGetBoundedNudgesWithoutLosingCodeOrMetadata() {
        val guard = AgentReadRepeatGuard()
        val original = result()
        for (count in 1..10) {
            val observed = guard.observe("read_file", original)
            val body = JSONObject(observed.content)
            assertEquals(count in setOf(3, 5, 8), body.has("repeat_read_reminder"))
            assertEquals("CODE", body.getString("content"))
            assertEquals("r1", body.getString("revision"))
            assertTrue(observed.sensitive)
            assertEquals("trace", observed.command)
            assertEquals(original.images, observed.images)
            guard.observe("read_file", result(path = "other-$count"))
        }
        assertFalse(JSONObject(original.content).has("repeat_read_reminder"))
        guard.reset()
        assertSame(original, guard.observe("read_file", original))
    }

    @Test fun newVersionsRangesContentAndIdentitiesAreNotConfusedWithRepeats() {
        val guard = AgentReadRepeatGuard()
        repeat(2) { guard.observe("read_file", result()) }
        for (result in listOf(result(revision = "r2"), result(offset = 4), result(content = "NEW"),
            result(path = "other.kt"), result().let { it.copy(content = JSONObject(it.content).put("identity", "root").toString()) })) {
            assertSame(result, guard.observe("read_file", result))
        }
        assertTrue(JSONObject(guard.observe("read_file", result()).content).has("repeat_read_reminder"))
    }

    @Test fun nonReadsErrorsAndNonJsonOutputsPassThroughUnmodified() {
        val guard = AgentReadRepeatGuard()
        val success = result()
        val error = success.copy(content = JSONObject(success.content).put("ok", false).toString())
        val raw = AgentModelClient.ToolResult("raw output")
        repeat(10) {
            assertSame(success, guard.observe("stat_file", success))
            assertSame(error, guard.observe("read_file", error))
            assertSame(raw, guard.observe("read_file", raw))
        }
    }
}
