package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileToolSearchTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun operations(): AgentFileOperations = AgentFileOperations(LocalFileToolBackend(temporary.root))

    @Test fun globContinuesWithoutDuplicatesAndCanIncludeHiddenFiles() {
        temporary.newFile("a.kt")
        temporary.newFile(".hidden.kt")
        temporary.newFolder("nested").also { File(it, "b.kt").writeText("") }
        temporary.newFile("unrelated.txt")
        val operations = operations()
        var cursor: String? = null
        val paths = mutableListOf<String>()
        var rounds = 0
        do {
            val result = JSONObject(operations.globFiles("", "**/*.kt", limit = 1, cursor = cursor))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val matches = result.getJSONArray("matches")
            for (index in 0 until matches.length()) paths += matches.getJSONObject(index).getString("relative_path")
            cursor = if (result.isNull("next_cursor")) null else result.getString("next_cursor")
            assertTrue(++rounds < 10)
        } while (cursor != null)
        assertEquals(setOf("a.kt", "nested/b.kt"), paths.toSet())
        assertEquals(2, paths.size)
        val withHidden = JSONObject(operations.globFiles("", "**/*.kt", includeHidden = true))
        assertEquals(3, withHidden.getInt("count"))
    }

    @Test fun grepFindsLiteralAcrossUtf8ChunksWithoutRepeatingLongLine() {
        temporary.newFile("text.txt").writeText("x".repeat(15_998) + "中文needle" + "x".repeat(20_000) + "needle\nneedle\n")
        val result = JSONObject(operations().grepFiles("", "中文needle"))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(1, result.getInt("count"))
        assertEquals(15_998, result.getJSONArray("matches").getJSONObject(0).getInt("match_offset_bytes"))
        val all = JSONObject(operations().grepFiles("", "needle"))
        assertEquals(2, all.getInt("count"))
    }

    @Test fun byteBudgetCursorResumesInLargeFileWithoutRescanningItsPrefix() {
        temporary.newFile("large.txt").writeText("x".repeat((FileToolSearch.MAX_BYTES + 40_000)) + "target\n")
        val first = JSONObject(operations().grepFiles("", "target"))
        assertEquals(0, first.getInt("count"))
        assertEquals("byte_limit", first.getString("stop_reason"))
        assertTrue(first.getBoolean("truncated"))
        val second = JSONObject(operations().grepFiles("", "target", cursor = first.getString("next_cursor")))
        assertTrue(second.toString(), second.getBoolean("ok"))
        assertEquals(1, second.getInt("count"))
        assertEquals((FileToolSearch.MAX_BYTES + 40_000), second.getJSONArray("matches").getJSONObject(0).getInt("match_offset_bytes"))
        assertTrue(second.getInt("bytes_scanned") < 50_000)
    }

    @Test fun resultLimitCanContinueWithinOneFile() {
        temporary.newFile("lines.txt").writeText("hit one\nhit two\nhit three\n")
        val lines = mutableListOf<Long>()
        var cursor: String? = null
        var rounds = 0
        do {
            val result = JSONObject(operations().grepFiles("", "hit", limit = 1, cursor = cursor))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val matches = result.getJSONArray("matches")
            for (index in 0 until matches.length()) lines += matches.getJSONObject(index).getLong("line")
            cursor = if (result.isNull("next_cursor")) null else result.getString("next_cursor")
            assertTrue(++rounds < 8)
        } while (cursor != null)
        assertEquals(listOf(1L, 2L, 3L), lines)
    }

    @Test fun explicitFileSearchKeepsCaseLineNumbersAndPagination() {
        val file = temporary.newFile(".one.txt").apply { writeText("中 Hit\nmiss\nhit two\nhit three\n") }
        temporary.newFile("unrelated.txt").writeText("hit must not be searched")
        val lines = mutableListOf<Int>()
        var cursor: String? = null
        do {
            val page = JSONObject(operations().grepFiles(file.name, "hit", limit = 1, cursor = cursor, caseSensitive = false))
            assertTrue(page.toString(), page.getBoolean("ok"))
            val matches = page.getJSONArray("matches")
            for (i in 0 until matches.length()) {
                val match = matches.getJSONObject(i)
                assertEquals(file.canonicalPath, match.getString("path"))
                assertEquals(file.name, match.getString("relative_path"))
                lines += match.getInt("line")
            }
            cursor = page.optString("next_cursor").takeUnless { page.isNull("next_cursor") }
        } while (cursor != null)
        assertEquals(listOf(1, 3, 4), lines)
        assertEquals(2, JSONObject(operations().grepFiles(file.name, "hit")).getInt("count"))
        assertEquals(0, JSONObject(operations().grepFiles(file.name, "hit", glob = "*.kt")).getInt("count"))
        assertEquals("NOT_DIRECTORY", JSONObject(operations().globFiles(file.name, "**/*")).getString("code"))
    }

    @Test fun explicitFileSearchResumesByteBudgetAndRejectsChangedFilesAndQueries() {
        val file = temporary.newFile("large-single.txt").apply { writeText("x".repeat((FileToolSearch.MAX_BYTES + 40_000)) + "target\n") }
        val first = JSONObject(operations().grepFiles(file.name, "target"))
        assertEquals("byte_limit", first.getString("stop_reason"))
        val cursor = first.getString("next_cursor")
        val second = JSONObject(operations().grepFiles(file.name, "target", cursor = cursor))
        assertEquals((FileToolSearch.MAX_BYTES + 40_000), second.getJSONArray("matches").getJSONObject(0).getInt("match_offset_bytes"))
        assertTrue(second.getBoolean("complete"))
        assertEquals("INVALID_CURSOR", JSONObject(operations().grepFiles(file.name, "different", cursor = cursor)).getString("code"))
        file.appendText("updated")
        assertEquals("STALE_CURSOR", JSONObject(operations().grepFiles(file.name, "target", cursor = cursor)).getString("code"))
    }

    @Test fun explicitBinaryFileIsReportedAsSkippedWithoutTraversingItsParent() {
        val file = temporary.newFile("binary.dat").apply { writeBytes(byteArrayOf(65, 0, 66)) }
        val result = JSONObject(operations().grepFiles(file.name, "A"))
        assertTrue(result.getBoolean("ok"))
        assertFalse(result.getBoolean("complete"))
        assertEquals(1, result.getInt("total_skipped"))
        assertEquals("BINARY_FILE", result.getJSONArray("skipped").getJSONObject(0).getString("reason"))
        assertEquals(file.name, result.getJSONArray("skipped").getJSONObject(0).getString("relative_path"))
    }

    @Test fun skippedBinaryAndSymlinkAreReportedAsIncompleteCoverage() {
        temporary.newFile("binary.dat").writeBytes(byteArrayOf(1, 0, 2))
        val file = temporary.newFile("text.txt").apply { writeText("needle") }
        Files.createSymbolicLink(File(temporary.root, "link.txt").toPath(), file.toPath())
        val result = JSONObject(operations().grepFiles("", "needle"))
        assertEquals(1, result.getInt("count"))
        assertEquals(2, result.getInt("total_skipped"))
        assertFalse(result.getBoolean("complete"))
        assertTrue(result.isNull("next_cursor"))
    }

    @Test fun cursorRejectsChangedDirectoryAndDifferentQuery() {
        temporary.newFile("a.txt").writeText("hit")
        temporary.newFile("b.txt").writeText("hit")
        val first = JSONObject(operations().grepFiles("", "hit", limit = 1))
        val cursor = first.getString("next_cursor")
        assertEquals("INVALID_CURSOR", JSONObject(operations().grepFiles("", "other", cursor = cursor)).getString("code"))
        Files.setLastModifiedTime(temporary.root.toPath(), FileTime.fromMillis(temporary.root.lastModified() + 10_000))
        assertEquals("STALE_CURSOR", JSONObject(operations().grepFiles("", "hit", cursor = cursor)).getString("code"))
    }

    @Test fun globalBackendFailureIsNotReportedAsSuccessfulPartialSearch() {
        temporary.newFile("text.txt").writeText("needle")
        val local = LocalFileToolBackend(temporary.root)
        for (code in listOf("ROOT_REQUIRED", "FILE_TIMEOUT", "FILE_PROCESS_FAILED", "CANCELLED")) {
            val backend = object : FileToolBackend by local {
                override fun readBytes(path: String, offset: Long, limit: Int): ByteArray =
                    throw FileToolException(code, "文件操作不可用")
            }
            val result = JSONObject(AgentFileOperations(backend).grepFiles("", "needle"))
            assertFalse(result.getBoolean("ok"))
            assertEquals(code, result.getString("code"))
        }
    }

    @Test fun globCursorCannotBeReusedForLiteralNullGrepQuery() {
        temporary.newFile("a.txt").writeText("null")
        temporary.newFile("b.txt").writeText("null")
        val first = JSONObject(operations().globFiles("", "**/*", limit = 1))
        val cursor = first.getString("next_cursor")
        val second = JSONObject(operations().grepFiles("", "null", cursor = cursor))
        assertFalse(second.getBoolean("ok"))
        assertEquals("INVALID_CURSOR", second.getString("code"))
    }

    @Test fun softTimeLimitKeepsProgressAndAUsableCursorAcrossSmallFiles() {
        repeat(5) { temporary.newFile("entry-$it.txt").writeText("hit") }
        val local = LocalFileToolBackend(temporary.root)
        var time = 0L
        val backend = object : FileToolBackend by local {
            override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
                time += 3_000_000_000L
                return local.readBytes(path, offset, limit)
            }
        }
        val search = FileToolSearch(backend, nanoTime = { time })
        val paths = mutableListOf<String>()
        var cursor: String? = null
        var timedPage = false
        var rounds = 0
        do {
            val result = JSONObject(search.grep("", "hit", "**/*", 200, cursor, false, true))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val matches = result.getJSONArray("matches")
            for (index in 0 until matches.length()) paths += matches.getJSONObject(index).getString("relative_path")
            if (result.getString("stop_reason") == "time_limit") {
                timedPage = true
                assertTrue(result.getBoolean("partial"))
                assertTrue(result.getInt("bytes_scanned") > 0)
            }
            cursor = if (result.isNull("next_cursor")) null else result.getString("next_cursor")
            assertTrue(++rounds < 10)
        } while (cursor != null)
        assertTrue(timedPage)
        assertEquals((0 until 5).map { "entry-$it.txt" }.toSet(), paths.toSet())
        assertEquals(5, paths.size)
    }

    @Test fun snapshotAndFallbackHaveIdenticalLiteralUnicodeHiddenAndGlobSemantics() {
        temporary.newFile(".gitignore").writeText("ignored.txt\n")
        temporary.newFile("ignored.txt").writeText("Literal[.*] 中文😀\nsecond LITERAL[.*]\n")
        val specialName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "line 'quote $.txt"
        } else {
            "line\n'quote $;*.txt"
        }
        temporary.newFile(specialName).writeText("literal[.*]\n")
        temporary.newFile("other.kt").writeText("literal[.*]\n")
        temporary.newFolder(".hidden").also { File(it, "inside.txt").writeText("literal[.*]\n") }
        temporary.newFolder("nested").also { File(it, "inside.txt").writeText("literal[.*]\n") }
        val local = LocalFileToolBackend(temporary.root)
        val fallback = object : FileToolBackend by local {}
        for (hidden in listOf(false, true)) for (sensitive in listOf(false, true)) for (glob in listOf("**/*", "**/*.txt", "*.kt")) {
            val fast = JSONObject(AgentFileOperations(local).grepFiles("", "literal[.*]", glob = glob, includeHidden = hidden, caseSensitive = sensitive))
            val slow = JSONObject(AgentFileOperations(fallback).grepFiles("", "literal[.*]", glob = glob, includeHidden = hidden, caseSensitive = sensitive))
            assertJsonSimilar(slow, fast)
        }
    }

    @Test fun batchingEmptyAndSmallFilesStaysBoundedAndAvoidsPerChunkIo() {
        repeat(70) { temporary.newFile("small-$it.txt").writeText(if (it % 2 == 0) "" else "miss\n") }
        val local = LocalFileToolBackend(temporary.root)
        var batches = 0
        var verified = 0
        val requested = mutableListOf<String>()
        val backend = object : FileToolBackend by local, FileToolSearchBackend {
            override fun readBytes(path: String, offset: Long, limit: Int): ByteArray = error("search must use snapshots")
            override fun readSearchBatch(requests: List<FileToolSearchRequest>): List<Result<FileToolSearchRead>> {
                batches++
                assertTrue(requests.size in 1..32)
                assertTrue(requests.sumOf { it.limit } <= 256 * 1024)
                requested += requests.map { it.path }
                return local.readSearchBatch(requests)
            }
            override fun verifySearchBatch(stats: List<FileToolStat>) { verified += stats.size; local.verifySearchBatch(stats) }
        }
        val result = JSONObject(AgentFileOperations(backend).grepFiles("", "absent"))
        assertTrue(result.toString(), result.getBoolean("complete"))
        assertEquals(70, requested.size)
        assertEquals(70, requested.toSet().size)
        assertEquals(70, verified)
        assertEquals(3, batches)
    }

    @Test fun prefetchedFileChangesAreDetectedBeforePublishingResults() {
        val file = temporary.newFile("mutable.txt").apply { writeText("needle\n") }
        val local = LocalFileToolBackend(temporary.root)
        val backend = object : FileToolBackend by local, FileToolSearchBackend by local {
            override fun readSearchBatch(requests: List<FileToolSearchRequest>): List<Result<FileToolSearchRead>> =
                local.readSearchBatch(requests).also { file.appendText("changed") }
        }
        val result = JSONObject(AgentFileOperations(backend).grepFiles("", "needle"))
        assertFalse(result.getBoolean("ok"))
        assertEquals("STALE_CURSOR", result.getString("code"))
    }

    @Test fun substitutedSymlinkIsNeverReadAsASearchSnapshot() {
        val file = temporary.newFile("entry.txt").apply { writeText("old") }
        val target = temporary.newFile("target.txt").apply { writeText("secret") }
        val backend = LocalFileToolBackend(temporary.root)
        Files.delete(file.toPath())
        Files.createSymbolicLink(file.toPath(), target.toPath())
        val result = backend.readSearchBatch(listOf(FileToolSearchRequest(file.path, 0, 64))).single()
        assertTrue(result.isFailure)
        assertEquals("STALE_CURSOR", (result.exceptionOrNull() as FileToolException).code)
    }

    @Test fun snapshotUtf8BoundaryAndCursorContinuationAgreeWithFallback() {
        temporary.newFile("utf8.txt").writeText("x".repeat(63_999) + "😀中文target" + "x".repeat(70_000) + "\ntarget\n")
        val local = LocalFileToolBackend(temporary.root)
        val fallback = object : FileToolBackend by local {}
        var cursor: String? = null
        var rounds = 0
        do {
            val fast = JSONObject(AgentFileOperations(local).grepFiles("", "😀中文target", limit = 1, cursor = cursor))
            val slow = JSONObject(AgentFileOperations(fallback).grepFiles("", "😀中文target", limit = 1, cursor = cursor))
            assertJsonSimilar(slow, fast)
            if (rounds == 0) assertEquals(63_999, fast.getJSONArray("matches").getJSONObject(0).getInt("match_offset_bytes"))
            cursor = if (fast.isNull("next_cursor")) null else fast.getString("next_cursor")
            assertTrue(++rounds < 4)
        } while (cursor != null)
    }

    @Test fun snapshotErrorsPreservePerFileCoverageAndGlobalFailures() {
        temporary.newFile("entry.txt").writeText("hit")
        val local = LocalFileToolBackend(temporary.root)
        for (code in listOf("FILE_ACCESS_DENIED", "ROOT_REQUIRED", "FILE_TIMEOUT", "CANCELLED", "STALE_CURSOR")) {
            val backend = object : FileToolBackend by local, FileToolSearchBackend by local {
                override fun readSearchBatch(requests: List<FileToolSearchRequest>) = requests.map {
                    Result.failure<FileToolSearchRead>(FileToolException(code, "test"))
                }
            }
            val result = JSONObject(AgentFileOperations(backend).grepFiles("", "hit"))
            if (code == "FILE_ACCESS_DENIED") {
                assertTrue(result.getBoolean("ok"))
                assertFalse(result.getBoolean("complete"))
                assertEquals(code, result.getJSONArray("skipped").getJSONObject(0).getString("reason"))
            } else {
                assertFalse(result.getBoolean("ok"))
                assertEquals(code, result.getString("code"))
            }
        }
    }

    @Test fun fastPathSoftDeadlineKeepsProgressAndDiscardsPrefetchBetweenPages() {
        repeat(5) { temporary.newFile("entry-$it.txt").writeText("hit\n") }
        val local = LocalFileToolBackend(temporary.root)
        var time = 0L
        val backend = object : FileToolBackend by local, FileToolSearchBackend by local {
            override fun readSearchBatch(requests: List<FileToolSearchRequest>) = local.readSearchBatch(requests).also { time += 6_000_000_000 }
        }
        val search = FileToolSearch(backend, nanoTime = { time })
        val paths = mutableListOf<String>()
        var cursor: String? = null
        var rounds = 0
        do {
            val result = JSONObject(search.grep("", "hit", "**/*", 200, cursor, false, true))
            assertTrue(result.toString(), result.getBoolean("ok"))
            val matches = result.getJSONArray("matches")
            for (i in 0 until matches.length()) paths += matches.getJSONObject(i).getString("relative_path")
            cursor = if (result.isNull("next_cursor")) null else result.getString("next_cursor")
            assertTrue(++rounds < 8)
        } while (cursor != null)
        assertEquals(5, paths.size)
        assertEquals(5, paths.toSet().size)
    }

    @Test fun cursorBindsEverySearchConditionAndCanCrossFastAndFallbackBackends() {
        temporary.newFile("a.txt").writeText("hit\nhit\n")
        val local = LocalFileToolBackend(temporary.root)
        val first = JSONObject(AgentFileOperations(local).grepFiles("", "hit", limit = 1))
        val cursor = first.getString("next_cursor")
        for ((hidden, sensitive, glob) in listOf(Triple(true, true, "**/*"), Triple(false, false, "**/*"), Triple(false, true, "*.txt"))) {
            assertEquals("INVALID_CURSOR", JSONObject(operations().grepFiles("", "hit", cursor = cursor,
                includeHidden = hidden, caseSensitive = sensitive, glob = glob)).getString("code"))
        }
        val fallback = object : FileToolBackend by local {}
        val next = JSONObject(AgentFileOperations(fallback).grepFiles("", "hit", limit = 2, cursor = cursor))
        assertEquals(2, next.getJSONArray("matches").getJSONObject(0).getInt("line"))
        val otherIdentity = LocalFileToolBackend(temporary.root, identity = "root")
        assertEquals("INVALID_CURSOR", JSONObject(AgentFileOperations(otherIdentity).grepFiles("", "hit", cursor = cursor)).getString("code"))
    }

    @Test fun prefetchRespectsEntryBudgetAndResumesWithoutReadingAheadOfTheCursor() {
        val local = LocalFileToolBackend(temporary.root)
        val revision = local.stat(temporary.root.path).revision
        val requested = mutableListOf<String>()
        val backend = object : FileToolBackend by local, FileToolSearchBackend {
            override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?): FileToolDirectoryPage {
                assertEquals(revision, expectedRevision)
                val end = minOf(offset + limit, FileToolSearch.MAX_ENTRIES + 1)
                val entries = (offset until end).map { n -> FileToolStat("$path/file-$n", "file-$n", FileToolKind.FILE, 0, "unchanged") }
                return FileToolDirectoryPage(entries, end, end <= FileToolSearch.MAX_ENTRIES, revision)
            }
            override fun readSearchBatch(requests: List<FileToolSearchRequest>) = requests.map { request ->
                requested += request.path
                Result.success(FileToolSearchRead(FileToolStat(request.path, File(request.path).name,
                    FileToolKind.FILE, 0, "unchanged"), request.offset, byteArrayOf()))
            }
            override fun verifySearchBatch(stats: List<FileToolStat>) = Unit
        }
        val search = FileToolSearch(backend, nanoTime = { 0L })
        val first = JSONObject(search.grep("", "hit", "**/*", 200, null, false, true))
        assertEquals("entry_limit", first.getString("stop_reason"))
        assertEquals(FileToolSearch.MAX_ENTRIES, first.getInt("entries_scanned"))
        assertEquals(FileToolSearch.MAX_ENTRIES, requested.size)
        assertFalse(requested.any { it.endsWith("file-${FileToolSearch.MAX_ENTRIES}") })
        val last = JSONObject(search.grep("", "hit", "**/*", 200, first.getString("next_cursor"), false, true))
        assertTrue(last.toString(), last.getBoolean("complete"))
        assertEquals(1, last.getInt("entries_scanned"))
        assertEquals(FileToolSearch.MAX_ENTRIES + 1, requested.toSet().size)
    }

    @Test fun skippedBinarySnapshotsCannotBypassTheIoByteBudget() {
        val local = LocalFileToolBackend(temporary.root)
        val revision = local.stat(temporary.root.path).revision
        var requestedBytes = 0
        val backend = object : FileToolBackend by local, FileToolSearchBackend {
            override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?): FileToolDirectoryPage {
                val end = minOf(offset + limit, 100)
                return FileToolDirectoryPage((offset until end).map { FileToolStat("$path/binary-$it", "binary-$it",
                    FileToolKind.FILE, 64_000, "fixed") }, end, end < 100, revision)
            }
            override fun readSearchBatch(requests: List<FileToolSearchRequest>) = requests.map { request ->
                requestedBytes += request.limit
                Result.success(FileToolSearchRead(FileToolStat(request.path, File(request.path).name,
                    FileToolKind.FILE, 64_000, "fixed"), request.offset, ByteArray(request.limit)))
            }
            override fun verifySearchBatch(stats: List<FileToolStat>) = Unit
        }
        val search = FileToolSearch(backend, nanoTime = { 0L })
        val first = JSONObject(search.grep("", "hit", "**/*", 200, null, false, true))
        assertEquals("byte_limit", first.getString("stop_reason"))
        assertTrue(first.getBoolean("partial"))
        assertTrue(requestedBytes <= FileToolSearch.MAX_BYTES)
        assertEquals(0, first.getInt("bytes_scanned"))
        requestedBytes = 0
        val last = JSONObject(search.grep("", "hit", "**/*", 200, first.getString("next_cursor"), false, true))
        assertTrue(last.toString(), last.getBoolean("ok"))
        assertEquals(100, last.getInt("total_skipped"))
        assertTrue(last.isNull("next_cursor"))
        assertFalse(last.getBoolean("complete"))
        assertTrue(requestedBytes <= FileToolSearch.MAX_BYTES)
    }

    @Test fun dispatcherAndSharedLocalBackendAreSafeForConcurrentReadOnlyCalls() {
        temporary.newFile("source.txt").writeText("needle 中文😀\n")
        val backend = LocalFileToolBackend(temporary.root)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val names = listOf("read_file", "stat_file", "list_directory", "glob_files", "grep_files")
            val tasks = List(40) { n -> java.util.concurrent.Callable {
                val name = names[n % names.size]
                val args = JSONObject().put("path", if (name in setOf("read_file", "stat_file")) "source.txt" else "")
                    .put("pattern", "**/*.txt").put("query", "needle")
                val result = JSONObject(FileToolDispatcher.execute(backend, name, args))
                assertTrue(result.toString(), result.getBoolean("ok"))
                if (name in setOf("glob_files", "grep_files")) assertEquals(1, result.getInt("count"))
                if (name == "read_file") assertEquals("needle 中文😀\n", result.getString("content"))
                name
            } }
            val results = pool.invokeAll(tasks, 10, java.util.concurrent.TimeUnit.SECONDS).map { it.get() }
            assertEquals(40, results.size)
        } finally { pool.shutdownNow() }
    }

    @Test(timeout = 1_000) fun globWildcardsDoNotEnterRegexBacktrackingAndQuestionMatchesOneCodePoint() {
        assertFalse(FileToolGlob("**/".repeat(100) + "missing").matches("part/".repeat(100) + "file"))
        assertTrue(FileToolGlob("?.txt").matches("😀.txt"))
        assertFalse(FileToolGlob("*.txt").matches("nested/a.txt"))
        assertTrue(FileToolGlob("**/*.txt").matches("a.txt"))
        assertTrue(FileToolGlob("**/*.txt").matches("nested/a.txt"))
    }

    private fun assertJsonSimilar(expected: JSONObject, actual: JSONObject, path: String = "$") {
        val expectedKeys = expected.keys().asSequence().toSet()
        val actualKeys = actual.keys().asSequence().toSet()
        assertEquals("keys at $path", expectedKeys, actualKeys)
        for (key in expectedKeys) {
            assertJsonValueSimilar(expected.get(key), actual.get(key), "$path.$key")
        }
    }

    private fun assertJsonValueSimilar(expected: Any?, actual: Any?, path: String) {
        when (expected) {
            is JSONObject -> {
                assertTrue("expected JSONObject at $path", actual is JSONObject)
                assertJsonSimilar(expected, actual as JSONObject, path)
            }
            is org.json.JSONArray -> {
                assertTrue("expected JSONArray at $path", actual is org.json.JSONArray)
                val actualArray = actual as org.json.JSONArray
                assertEquals("length at $path", expected.length(), actualArray.length())
                for (i in 0 until expected.length()) {
                    assertJsonValueSimilar(expected.get(i), actualArray.get(i), "$path[$i]")
                }
            }
            else -> assertEquals("value at $path", expected, actual)
        }
    }
}
