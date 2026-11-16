package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.testing.PosixIntegrationTest
import org.junit.experimental.categories.Category
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@Category(PosixIntegrationTest::class)
class ShellFileToolBackendTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvePreservesQuotedPathsAndUsesEnvironmentHome() {
        val backend = newBackend { command, _, _ ->
            runShell("readlink() { printf '%s\\n' \"\$3\"; }; $command")
        }
        listOf(
            "/workspace/plain", "/workspace/a b", "/workspace/a'b", "/workspace/line\nend\n",
            "/workspace/\$(printf injected)", "/workspace/`printf injected`", "/workspace/*?[x]",
        ).forEach { path -> assertEquals(path, backend.resolve(path)) }
        assertEquals("/workspace/-leading-option", backend.resolve("-leading-option"))
        assertEquals("/guest-home", backend.resolve("~"))
        assertEquals("/guest-home/a'b", backend.resolve("~/a'b"))
    }

    @Test
    fun resolveDoesNotCollapseDotDotBeforeFollowingRemoteSymlink() {
        var requested = ""
        val backend = newBackend { command, _, _ ->
            requested = command
            result("/elsewhere/file\n")
        }
        assertEquals("/elsewhere/file", backend.resolve("link/../file"))
        assertTrue(requested, requested.contains("'/workspace/link/../file'"))
        val missing = newBackend { _, _, _ -> result(exit = 1) }
        assertEquals("/workspace/link/../new/file", missing.resolve("link/../new/file"))
        assertFailure("FILE_COMMAND_FAILED") { newBackend { _, _, _ -> result(exit = 127) }.resolve("file") }
        assertFailure("INVALID_PATH") { backend.resolve("bad\u0000path") }
    }

    @Test
    fun statPassesRealNewlineFormatAndPathAsSeparateLiteralArguments() {
        val file = temporaryFolder.newFile("-a'b\n\$(printf injected)`printf injected`")
        val arguments = File(temporaryFolder.root, "stat-arguments")
        val backend = newBackend { command, _, _ ->
            val stub = "stat() { printf '%s\\000' \"\$@\" > ${shellQuote(arguments.absolutePath)}; " +
                "printf '7\\n1:2:7:10:11:modified:changed\\n'; }; "
            runShell(stub + command)
        }
        val stat = backend.stat(file.absolutePath)
        assertEquals(FileToolKind.FILE, stat.kind)
        assertEquals(7L, stat.sizeBytes)
        val args = arguments.readText().split('\u0000').dropLast(1)
        assertEquals(listOf("-c", "%s\n%d:%i:%s:%Y:%Z:%y:%z", "--", file.absolutePath), args)
    }

    @Test
    fun statRejectsInvalidOrTruncatedMetadata() {
        listOf(
            "unknown\n7\n1:2:7:10:11:modified:changed\n",
            "file\n-1\n1:2:-1:10:11:modified:changed\n",
            "file\n7\\n1:2:7:10:11:modified:changed\n",
            "file\n7\n",
            "file\n7\n1:2:8:10:11:modified:changed\n",
            "file\n7\n?:2:7:10:11:modified:changed\n",
        ).forEach { output ->
            assertFailure("FILE_METADATA_INVALID") { newBackend { _, _, _ -> result(output) }.stat("/workspace/a") }
        }
        assertFailure("UNSUPPORTED_ENCODING") {
            newBackend { _, _, _ -> OneShotShellResult(0, byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf()) }.stat("/workspace/a")
        }
        assertFailure("FILE_OUTPUT_LIMIT") {
            newBackend { _, _, _ -> result(metadata(), truncated = true) }.stat("/workspace/a")
        }
    }

    @Test
    fun blockReadsReturnExactUnalignedByteRangeAndHandleEof() {
        val bytes = ByteArray(20_000) { (it % 251).toByte() }
        val backend = newBackend { command, input, maxBytes ->
            assertEquals(null, input)
            assertTrue(command, command.contains("dd if='/workspace/a' bs=4096"))
            val blockOffset = Regex("skip=([0-9]+)").find(command)!!.groupValues[1].toLong()
            val count = Regex("count=([0-9]+)").find(command)!!.groupValues[1].toInt()
            assertEquals(count * 4096, maxBytes)
            val start = (blockOffset * 4096).coerceAtMost(bytes.size.toLong()).toInt()
            val end = (start + maxBytes).coerceAtMost(bytes.size)
            OneShotShellResult(0, bytes.copyOfRange(start, end), byteArrayOf())
        }
        listOf(0L to 4, 4095L to 6, 4096L to 4096, 8193L to 7000, 19_998L to 8, 30_000L to 8).forEach { (offset, limit) ->
            val start = offset.coerceAtMost(bytes.size.toLong()).toInt()
            assertArrayEquals(bytes.copyOfRange(start, (start + limit).coerceAtMost(bytes.size)), backend.readBytes("/workspace/a", offset, limit))
        }
        assertFailure("INVALID_ARGUMENT") { backend.readBytes("/workspace/a", -1, 2) }
        assertFailure("INVALID_ARGUMENT") { backend.readBytes("/workspace/a", 0, 0) }
    }

    @Test
    fun listKeepsNulDelimitedNamesAndUsesStablePagination() {
        val paths = listOf("/workspace/z", "/workspace/a\n'b", "/workspace/-first")
        var batch = 0
        val backend = newBackend { command, input, _ ->
            when {
                "find " in command -> result(paths.joinToString("\u0000", postfix = "\u0000"))
                command == "sh -s" -> {
                    val selected = if (batch++ == 0) paths.sorted().take(2) else listOf("/workspace/z")
                    selected.forEach { assertTrue(input!!.decodeToString().contains(shellQuote(it))) }
                    result(selected.joinToString("") { metadata() + '\u0000' })
                }
                "stat -c" in command && "if [ -L '/workspace' ]" in command -> result(metadata("directory"))
                else -> throw AssertionError("目录条目没有使用批量查询")
            }
        }
        val first = backend.list("/workspace", 0, 2)
        assertEquals(paths.sorted().take(2), first.entries.map { it.path })
        assertTrue(first.hasMore)
        assertEquals(2, first.nextOffset)
        val last = backend.list("/workspace", first.nextOffset, 2, first.revision)
        assertEquals(listOf("/workspace/z"), last.entries.map { it.path })
        assertFalse(last.hasMore)
        assertEquals(3, last.nextOffset)
    }

    @Test
    fun fullDirectoryPageUsesFourExecutionsAndStdinForLongPaths() {
        val root = "/" + List(6) { "directory".repeat(20) }.joinToString("/")
        val paths = List(200) { "$root/entry-${it.toString().padStart(3, '0')}\n'\$(printf literal)" }
        var calls = 0
        var batches = 0
        val backend = newBackend { command, input, _ ->
            calls++
            when {
                "find " in command -> result(paths.joinToString("\u0000", postfix = "\u0000"))
                command == "sh -s" -> {
                    batches++
                    assertTrue(input!!.size > 131_072)
                    val script = input.decodeToString()
                    paths.forEach { assertTrue(script.contains(shellQuote(it))) }
                    result(paths.joinToString("") { metadata() + '\u0000' })
                }
                else -> result(metadata("directory"))
            }
        }
        val page = backend.list(root, 0, 200)
        assertEquals(paths, page.entries.map { it.path })
        assertEquals(4, calls)
        assertEquals(1, batches)
        assertFalse(page.hasMore)
    }

    @Test
    fun batchedStatScriptKeepsNewlinesAndQuotesOutOfRecordFraming() {
        val files = listOf("line\n'a", "\$(printf injected)", "-option").map { temporaryFolder.newFile(it) }
        val paths = files.map { it.absolutePath }.sorted()
        val backend = newBackend { command, input, _ ->
            when {
                "find " in command -> result(paths.joinToString("\u0000", postfix = "\u0000"))
                command == "sh -s" -> {
                    val stub = "stat() { printf '7\\n1:2:7:10:11:modified:changed\\n'; };\n"
                    runShell("sh -s", stub.toByteArray() + input!!)
                }
                else -> result(metadata("directory"))
            }
        }
        assertEquals(paths, backend.list(temporaryFolder.root.absolutePath, 0, 200).entries.map { it.path })
    }

    @Test
    fun batchRejectsMissingExtraAndUnterminatedMetadataRecords() {
        listOf(metadata(), metadata() + '\u0000', (metadata() + '\u0000').repeat(3)).forEach { batchOutput ->
            val backend = newBackend { command, _, _ ->
                when {
                    "find " in command -> result("/workspace/a\u0000/workspace/b\u0000")
                    command == "sh -s" -> result(batchOutput)
                    else -> result(metadata("directory"))
                }
            }
            assertFailure("FILE_METADATA_INVALID") { backend.list("/workspace", 0, 2) }
        }
    }

    @Test
    fun listDoesNotTreatTruncatedOrChangedEnumerationAsComplete() {
        listOf(result("/workspace/a"), result("/workspace/a\u0000", truncated = true)).forEach { listing ->
            val backend = newBackend { command, _, _ ->
                if ("find " in command) listing else result(metadata("directory"))
            }
            assertFailure(if (listing.outputTruncated) "FILE_OUTPUT_LIMIT" else "DIRECTORY_TOO_LARGE") {
                backend.list("/workspace", 0, 2)
            }
        }
        var stats = 0
        val changing = newBackend { command, _, _ ->
            if ("find " in command) result()
            else result(metadata("directory", version = (++stats).toString()))
        }
        assertFailure("STALE_CURSOR") { changing.list("/workspace", 0, 2) }
        var calls = 0
        val stale = newBackend { _, _, _ -> calls++; result(metadata("directory")) }
        assertFailure("STALE_CURSOR") { stale.list("/workspace", 0, 2, "old-revision") }
        assertEquals(1, calls)
    }

    @Test
    fun writesCheckHashBeforeRedirectionAndKeepReplacementOutOfScript() {
        val file = temporaryFolder.newFile("-a'b\n\$(printf injected)").apply { writeText("original") }
        val expectedHash = "a".repeat(64)
        val replacement = "replacement\n\$(printf should_stay_literal)".toByteArray()
        var match = false
        var writeCommand = ""
        val backend = newBackend { command, input, _ ->
            if (input == null) result(metadata(size = file.length()))
            else {
                writeCommand = command
                assertArrayEquals(replacement, input)
                assertFalse(command.contains("should_stay_literal"))
                val actualHash = if (match) expectedHash else "b".repeat(64)
                val stub = "sha256sum() { cat >/dev/null; printf '%s  -\\n' '$actualHash'; }; "
                runShell(stub + command, input)
            }
        }
        assertFailure("FILE_CHANGED") { backend.write(file.absolutePath, replacement, false, expectedSha256 = expectedHash) }
        assertEquals("original", file.readText())
        assertTrue(writeCommand.indexOf("sha256sum") < writeCommand.indexOf("cat >"))
        match = true
        val written = backend.write(file.absolutePath, replacement, false, expectedSha256 = expectedHash)
        assertFalse(written.atomic)
        assertArrayEquals(replacement, file.readBytes())
        backend.write(file.absolutePath, replacement, true)
        assertArrayEquals(replacement + replacement, file.readBytes())
    }

    @Test
    fun staleRevisionAndInvalidHashNeverSubmitWrite() {
        var calls = 0
        val backend = newBackend { _, input, _ ->
            calls++
            assertEquals(null, input)
            result(metadata())
        }
        assertFailure("FILE_CHANGED") { backend.write("/workspace/a", byteArrayOf(1), false, expectedRevision = "stale") }
        assertEquals(1, calls)
        assertFailure("INVALID_ARGUMENT") { backend.write("/workspace/a", byteArrayOf(1), false, expectedSha256 = "invalid") }
        assertEquals(1, calls)
    }

    @Test
    fun cancellationAndAuthorizationChangesPropagateBeforeAndAfterExecution() {
        var cancellation: String? = "CANCELLED"
        var calls = 0
        val backend = ShellFileToolBackend("linux", "user", "/workspace", "/guest-home", ensureActive = {
            cancellation?.let { throw FileToolException(it, "操作不可继续") }
        }) { _, _, _ ->
            calls++
            cancellation = "ROOT_REQUIRED"
            result("secret")
        }
        assertFailure("CANCELLED") { backend.readBytes("/workspace/a", 0, 3) }
        assertEquals(0, calls)
        cancellation = null
        assertFailure("ROOT_REQUIRED") { backend.readBytes("/workspace/a", 0, 3) }
        assertEquals(1, calls)
        assertFailure("CANCELLED") {
            newBackend { _, _, _ -> result(exit = -3, truncated = true) }.readBytes("/workspace/a", 0, 3)
        }
        assertFailure("FILE_TIMEOUT") { newBackend { _, _, _ -> result(exit = -2) }.readBytes("/workspace/a", 0, 3) }
        assertFailure("FILE_INPUT_FAILED") {
            newBackend { _, _, _ -> result(exit = SHELL_STDIN_FAILED_EXIT_CODE) }
                .write("/workspace/a", byteArrayOf(1), false)
        }
        assertFailure("FILE_ACCESS_DENIED") { newBackend { _, _, _ -> result(exit = 43) }.stat("/workspace/a") }
        assertFailure("FILE_HASH_UNAVAILABLE") {
            newBackend { _, _, _ -> result(exit = 45) }.write("/workspace/a", byteArrayOf(1), false, expectedSha256 = "a".repeat(64))
        }
    }

    @Test fun searchBatchReadsArbitraryBytesAtUnalignedOffsetsWithLiteralFilenames() {
        val repo = temporaryFolder.newFolder("repo")
        val file = File(repo, "-line\n' \$(printf injected)`printf injected`*.bin").apply {
            writeBytes(ByteArray(140_000) { (it % 251).toByte() })
        }
        var invocations = 0
        val backend = newBackend { command, input, maxBytes ->
            invocations++
            assertEquals("sh -s", command)
            assertTrue(input!!.decodeToString().contains(shellQuote(file.path)))
            assertTrue(maxBytes < 1024 * 1024)
            runShell(command, input)
        }
        val requests = listOf(0L to 7, 4095L to 9, 16_001L to 64_000, 139_998L to 10, 150_000L to 4)
            .map { (offset, limit) -> FileToolSearchRequest(file.path, offset, limit) }
        val reads = backend.readSearchBatch(requests)
        val bytes = file.readBytes()
        requests.zip(reads).forEach { (request, result) ->
            val snapshot = result.getOrThrow()
            assertEquals(request.offset, snapshot.offset)
            assertEquals(file.path, snapshot.stat.path)
            val from = minOf(request.offset, bytes.size.toLong()).toInt()
            assertArrayEquals(bytes.copyOfRange(from, minOf(from + request.limit, bytes.size)), snapshot.bytes)
        }
        assertEquals(1, invocations)
    }

    @Test fun searchBatchKeepsMissingAndNonRegularFailuresSeparateFromValidFiles() {
        val repo = temporaryFolder.newFolder("repo")
        val first = File(repo, "first").apply { writeText("hit") }
        val link = File(repo, "link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), first.toPath())
        val last = File(repo, "last").apply { writeText("last") }
        val backend = newBackend { command, input, _ -> runShell(command, input) }
        val results = backend.readSearchBatch(listOf(first, File(repo, "missing"), repo, link, last)
            .map { FileToolSearchRequest(it.path, 0, 64) })
        assertEquals("hit", results[0].getOrThrow().bytes.decodeToString())
        assertEquals("FILE_NOT_FOUND", (results[1].exceptionOrNull() as FileToolException).code)
        assertEquals("STALE_CURSOR", (results[2].exceptionOrNull() as FileToolException).code)
        assertEquals("STALE_CURSOR", (results[3].exceptionOrNull() as FileToolException).code)
        assertEquals("last", results[4].getOrThrow().bytes.decodeToString())
    }

    @Test fun searchBatchRejectsTruncatedExtraAndMalformedFrames() {
        val good = snapshotFrame()
        for (output in listOf(good.dropLast(1), good + "extra", good.replaceFirst("7\u0000payload", "999999\u0000payload"),
            good.replaceFirst("payload", "short"))) {
            assertFailure("FILE_CHANGED") {
                newBackend { _, _, _ -> result(output) }.readSearchBatch(listOf(FileToolSearchRequest("/a", 0, 7)))
            }
        }
        assertFailure("FILE_OUTPUT_LIMIT") {
            newBackend { _, _, _ -> result(good, truncated = true) }.readSearchBatch(listOf(FileToolSearchRequest("/a", 0, 7)))
        }
    }

    @Test fun searchBatchChecksReadStatusAndBothMetadataVersions() {
        for ((output, code) in listOf(snapshotFrame(after = metadata(version = "changed")) to "STALE_CURSOR",
            snapshotFrame(readStatus = "1") to "FILE_COMMAND_FAILED", snapshotFrame(afterStatus = "41") to "STALE_CURSOR")) {
            val read = newBackend { _, _, _ -> result(output) }.readSearchBatch(listOf(FileToolSearchRequest("/a", 0, 7))).single()
            assertTrue(read.isFailure)
            assertEquals(code, (read.exceptionOrNull() as FileToolException).code)
        }
    }

    @Test fun snapshotValidationDetectsMutationAndBatchesChecks() {
        val repo = temporaryFolder.newFolder("repo")
        val files = List(4) { File(repo, "entry-$it").apply { writeText("hit") } }
        var calls = 0
        val backend = newBackend { command, input, _ -> calls++; runShell(command, input) }
        val stats = backend.readSearchBatch(files.map { FileToolSearchRequest(it.path, 0, 64) }).map { it.getOrThrow().stat }
        assertEquals(1, calls)
        backend.verifySearchBatch(stats)
        assertEquals(2, calls)
        files[2].appendText("changed")
        assertFailure("STALE_CURSOR") { backend.verifySearchBatch(stats) }
        files[2].delete()
        assertFailure("STALE_CURSOR") { backend.verifySearchBatch(stats) }
    }

    @Test fun realShellSearchMatchesLocalWithSpecialHiddenIgnoredBinaryAndLinkedFiles() {
        val repo = temporaryFolder.newFolder("repo")
        File(repo, ".gitignore").writeText("ignored.txt\n")
        File(repo, "ignored.txt").writeText("literal[.*] 中文😀\nLITERAL[.*]\n")
        File(repo, "line\n' \$(printf literal).txt").writeText("literal[.*]\n")
        File(repo, ".hidden").mkdir()
        File(repo, ".hidden/inside.txt").writeText("literal[.*]\n")
        File(repo, "binary").writeBytes(byteArrayOf(0, 65))
        java.nio.file.Files.createSymbolicLink(File(repo, "link").toPath(), File(repo, "ignored.txt").toPath())
        val shell = newBackend { command, input, _ -> runShell(command, input) }
        val local = LocalFileToolBackend(repo)
        for (hidden in listOf(false, true)) for (sensitive in listOf(false, true)) {
            val fast = org.json.JSONObject(AgentFileOperations(shell).grepFiles(repo.path, "literal[.*]", includeHidden = hidden, caseSensitive = sensitive))
            val expected = org.json.JSONObject(AgentFileOperations(local).grepFiles(repo.path, "literal[.*]", includeHidden = hidden, caseSensitive = sensitive))
            assertTrue(fast.toString(), fast.getBoolean("ok"))
            assertEquals(expected.getInt("count"), fast.getInt("count"))
            assertEquals(expected.getInt("total_skipped"), fast.getInt("total_skipped"))
            val matches = fast.getJSONArray("matches")
            val expectedMatches = expected.getJSONArray("matches")
            val tuples = (0 until matches.length()).map { matches.getJSONObject(it).toString() }.toSet()
            assertEquals((0 until expectedMatches.length()).map { expectedMatches.getJSONObject(it).toString() }.toSet(), tuples)
        }
    }

    @Test fun searchFastPathWorksInBusyBoxAshWithoutRgOrGrep() {
        // Run this portability check when a BusyBox binary is installed or supplied by the harness.
        val busybox = System.getProperty("eta.test.busybox")?.let(::File)
            ?: listOf(File("/bin/busybox"), File("/usr/bin/busybox")).firstOrNull { it.canExecute() }
        org.junit.Assume.assumeTrue("BusyBox not available", busybox?.canExecute() == true)
        val bin = temporaryFolder.newFolder("busybox-bin")
        for (applet in listOf("sh", "stat", "dd", "find", "readlink")) {
            java.nio.file.Files.createSymbolicLink(File(bin, applet).toPath(), busybox!!.toPath())
        }
        val repo = temporaryFolder.newFolder("repo")
        File(repo, "a'\nfile.txt").writeText("needle 中文😀\nsecond NEEDLE\n")
        val backend = newBackend { command, input, _ ->
            runShell("PATH=${shellQuote(bin.path)} ${shellQuote(File(bin, "sh").path)} -c ${shellQuote(command)}", input)
        }
        val result = org.json.JSONObject(AgentFileOperations(backend).grepFiles(repo.path, "needle", caseSensitive = false))
        assertTrue(result.toString(), result.getBoolean("ok"))
        assertEquals(2, result.getInt("count"))
        assertTrue(result.getBoolean("complete"))
    }

    @Test fun searchBatchBudgetsAndCancellationAreCheckedBeforeLaunchingShell() {
        var calls = 0
        val backend = newBackend { _, _, _ -> calls++; result(snapshotFrame()) }
        for (requests in listOf(emptyList(), List(33) { FileToolSearchRequest("/a", 0, 1) },
            List(5) { FileToolSearchRequest("/a", 0, 64_000) }, listOf(FileToolSearchRequest("/a", -1, 1)),
            listOf(FileToolSearchRequest("/a", 0, 64_001)), listOf(FileToolSearchRequest("/bad\u0000path", 0, 1)))) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { backend.readSearchBatch(requests) }
        }
        assertEquals(0, calls)
        var cancelled = true
        val cancellation = ShellFileToolBackend("linux", "user", "/", "/", ensureActive = {
            if (cancelled) throw FileToolException("CANCELLED", "cancelled")
        }) { _, _, _ -> calls++; cancelled = true; result(snapshotFrame()) }
        assertFailure("CANCELLED") { cancellation.readSearchBatch(listOf(FileToolSearchRequest("/a", 0, 7))) }
        assertEquals(0, calls)
        cancelled = false
        assertFailure("CANCELLED") { cancellation.readSearchBatch(listOf(FileToolSearchRequest("/a", 0, 7))) }
        assertEquals(1, calls)
    }

    private fun snapshotFrame(after: String = metadata(), readStatus: String = "0", afterStatus: String = "0") =
        "0\u0000${metadata()}\u00007\u0000payload\u0000$readStatus\u0000$afterStatus\u0000$after\u0000"

    private fun newBackend(execute: (String, ByteArray?, Int) -> OneShotShellResult) =
        ShellFileToolBackend("linux", "user", "/workspace", "/guest-home", execute = execute)

    private fun metadata(kind: String = "file", size: Long = 7, version: String = "1") =
        "$kind\n$size\n1:2:$size:10:11:modified$version:changed$version\n"

    private fun result(output: String = "", exit: Int = 0, truncated: Boolean = false) =
        OneShotShellResult(exit, output.toByteArray(), byteArrayOf(), outputTruncated = truncated)

    private fun assertFailure(code: String, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $code")
        } catch (failure: FileToolException) {
            assertEquals(code, failure.code)
        }
    }

    private fun runShell(command: String, input: ByteArray? = null): OneShotShellResult {
        // 这里验证 Shell 脚本及文件保护合同；用文件供给输入，避免提前退出与父进程写管道竞争。
        val inputFile = temporaryFolder.newFile().apply { writeBytes(input ?: byteArrayOf()) }
        val process = ProcessBuilder("sh", "-c", command)
            .directory(temporaryFolder.root)
            .redirectInput(inputFile)
            .start()
        val output = process.inputStream.readBytes()
        val error = process.errorStream.readBytes()
        assertTrue("测试 Shell 没有正常结束", process.waitFor(5, TimeUnit.SECONDS))
        return OneShotShellResult(process.exitValue(), output, error)
    }
}
