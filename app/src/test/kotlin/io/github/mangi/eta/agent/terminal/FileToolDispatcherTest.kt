package io.github.mangi.eta.agent.terminal

import android.app.Application
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class FileToolDispatcherTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readDefaultsAgreeWithActualByteAndLineCaps() {
        val backend = LocalFileToolBackend(temporary.root)
        val bytes = temporary.newFile("large.kt").apply { writeText("a".repeat(70_000)) }
        val first = JSONObject(FileToolDispatcher.execute(backend, "read_file", JSONObject().put("path", bytes.name)))
        assertEquals(51_200, first.getInt("bytes_read"))
        assertEquals(1, first.getInt("total_lines"))
        val lines = temporary.newFile("lines.kt").apply { writeText("line\n".repeat(2_100)) }
        val page = JSONObject(FileToolDispatcher.execute(backend, "read_file", JSONObject().put("path", lines.name)))
        assertEquals(2_000, page.getInt("line_count"))
        assertEquals(2_100, page.getInt("total_lines"))
        assertEquals("line_limit", page.getString("stop_reason"))
    }

    @Test
    fun missingOrNonTextFileArgumentsNeverWrite() {
        val file = temporary.newFile("memo.txt").apply { writeText("original") }
        val backend = LocalFileToolBackend(temporary.root)
        val nonTextValues = listOf(JSONObject.NULL, JSONObject().put("text", "changed"), JSONArray().put("changed"))
        listOf("path", "content").forEach { key ->
            listOf<Any?>(null).plus(nonTextValues).forEach { value ->
                val args = JSONObject().put("path", file.name).put("content", "changed")
                if (value == null) args.remove(key) else args.put(key, value)
                rejectsText(key) { FileToolDispatcher.execute(backend, "write_file", args) }
                assertEquals("original", file.readText())
                assertEquals(listOf(file.name), temporary.root.list()!!.toList())
            }
        }
    }

    @Test
    fun missingOrNonTextReplacementNeverEdits() {
        val file = temporary.newFile("memo.txt").apply { writeText("original") }
        val backend = LocalFileToolBackend(temporary.root)
        listOf("old_text", "new_text").forEach { key ->
            listOf(null, JSONObject.NULL, JSONObject().put("text", "changed")).forEach { value ->
                val args = JSONObject().put("path", file.name).put("old_text", "original").put("new_text", "changed")
                if (value == null) args.remove(key) else args.put(key, value)
                rejectsText(key) { FileToolDispatcher.execute(backend, "edit_file", args) }
                assertEquals("original", file.readText())
            }
        }
    }

    @Test
    fun explicitEmptyTextStillClearsFilesAndDeletesMatches() {
        val file = temporary.newFile("memo.txt").apply { writeText("original") }
        val backend = LocalFileToolBackend(temporary.root)
        val edited = JSONObject(FileToolDispatcher.execute(backend, "edit_file", JSONObject()
            .put("path", file.name).put("old_text", "origin").put("new_text", "")))
        assertTrue(edited.toString(), edited.getBoolean("ok"))
        assertEquals("al", file.readText())
        val written = JSONObject(FileToolDispatcher.execute(backend, "write_file", JSONObject()
            .put("path", file.name).put("content", "")))
        assertTrue(written.toString(), written.getBoolean("ok"))
        assertEquals("", file.readText())
    }

    private fun rejectsText(key: String, operation: () -> Unit) {
        try {
            operation()
            throw AssertionError("accepted non-text $key")
        } catch (failure: JSONException) {
            assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(key))
        }
    }

    @Test fun mappedPathFailuresGiveGuidanceWithoutRewritingPathsOrChangingIdentity() {
        val cases = listOf(
            Triple("android", "/workspace/projects/Eta", "environment=linux"),
            Triple("android", "/workspace/mounts/private/file", "只在 Linux"),
            Triple("linux", "/data/data/app/files/config", "environment=android"),
            Triple("linux", "/data/user/0/app/files/config", "environment=android"),
            Triple("linux", "/data/local/tmp/eta/code.kt", "PRoot"),
            Triple("linux", "/sdcard/Documents/note", "/storage/emulated/0"),
        )
        for ((environment, path, guidance) in cases) {
            var resolved: String? = null
            val backend = object : FileToolBackend by LocalFileToolBackend(temporary.root) {
                override val environment = environment
                override val identity = "user"
                override fun resolve(path: String): String { resolved = path; return path }
                override fun stat(path: String): FileToolStat = throw FileToolException("FILE_NOT_FOUND", "missing")
            }
            val result = JSONObject(FileToolDispatcher.execute(backend, "read_file", JSONObject().put("path", path)))
            assertEquals("FILE_NOT_FOUND", result.getString("code"))
            assertTrue(result.toString(), result.getString("environment_hint").contains(guidance))
            assertEquals(path, resolved)
            assertEquals("user", backend.identity)
        }
        assertEquals(null, FileToolDispatcher.pathEnvironmentHint("/workspace-other/file", "android"))
        assertEquals(null, FileToolDispatcher.pathEnvironmentHint("/data/database/file", "linux"))
        assertEquals(null, FileToolDispatcher.pathEnvironmentHint("/workspace/projects/Eta", "linux"))
    }
}
