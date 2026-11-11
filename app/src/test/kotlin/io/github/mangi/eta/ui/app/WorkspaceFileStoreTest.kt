package io.github.mangi.eta.ui.app

import android.app.Application
import android.net.Uri
import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolStat
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import org.junit.Assert.assertArrayEquals
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(EtaRobolectricTestRunner::class)
@Config(application = Application::class, sdk = [36])
class WorkspaceFileStoreTest {
    @Test
    fun exportUsesThePrivilegedBackendForAnAppUnreadableArtifact() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val bytes = ByteArray(700_000) { (it % 256).toByte() }
        var reads = 0
        val store = WorkspaceFileStore(context) { root ->
            object : FileToolBackend by LocalFileToolBackend(root) {
                override val identity = "root"
                override fun stat(path: String) = FileToolStat(path, "root.bin", FileToolKind.FILE, bytes.size.toLong(), "revision")
                override fun readBytes(path: String, offset: Long, limit: Int): ByteArray {
                    reads++
                    return bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + limit))
                }
            }
        }
        val destination = File(context.cacheDir, "export.bin")
        store.exportFile("root.bin", Uri.fromFile(destination))
        assertArrayEquals(bytes, destination.readBytes())
        assertTrue(reads > 1)
    }

    @Test
    fun freshWorkspaceCanBeListedWithoutRuntimeInitialization() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = WorkspaceFileStore(context)

        assertTrue(store.list("").isEmpty())
        assertTrue(TerminalPrivateStorage.workspace(context.filesDir).isDirectory)
    }

    @Test
    fun existingWorkspaceFilesRemainVisible() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val legacy = File(context.filesDir, "terminal/workspace").apply { mkdirs() }
        File(legacy, "saved.txt").writeText("saved")

        val entries = WorkspaceFileStore(context).list("")

        assertEquals(listOf("saved.txt"), entries.map { it.path })
        assertEquals("saved", File(legacy, "saved.txt").readText())
    }
    @Test
    fun privilegedListingPaginatesWithoutDirectAppAccess() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "privileged-list").apply { mkdirs() }
        repeat(205) { File(root, "item-$it.txt").writeText("saved") }
        File(root, "folder").mkdirs()
        val store = WorkspaceFileStore(context, rootDirectory = root, directAccess = { false }) { LocalFileToolBackend(it) }
        val entries = store.list("")
        assertEquals(206, entries.size)
        assertEquals("folder", entries.first().name)
        assertTrue(entries.none { it.path.startsWith("/") })
    }

    @Test
    fun privilegedBinaryCopyIsNotLimitedByTheTextPreviewBudget() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "privileged-copy").apply { mkdirs() }
        val bytes = ByteArray((WORKSPACE_PREVIEW_MAX_BYTES + 17).toInt()) { (it % 256).toByte() }
        File(root, "large.apk").writeBytes(bytes)
        val store = WorkspaceFileStore(context, rootDirectory = root, directAccess = { false }) { LocalFileToolBackend(it) }
        val output = File(context.cacheDir, "shared_docs/large.apk")
        assertEquals(bytes.size.toLong(), store.copyTo("large.apk", output))
        assertArrayEquals(bytes, output.readBytes())
        try {
            store.readText("large.apk")
            org.junit.Assert.fail("oversized text preview must fail")
        } catch (expected: java.io.IOException) {
            assertEquals("WORKSPACE_FILE_TOO_LARGE", expected.message)
        }
    }

    @Test
    fun privilegedTextPreviewAndTraversalChecksUseTheBackend() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val root = File(context.cacheDir, "privileged-text").apply { mkdirs() }
        File(root, "note.txt").writeText("工作区迁移😀")
        val store = WorkspaceFileStore(context, rootDirectory = root, directAccess = { false }) { LocalFileToolBackend(it) }
        assertEquals("工作区迁移😀", store.readText("note.txt"))
        try {
            store.readText("../outside.txt")
            org.junit.Assert.fail("traversal must fail")
        } catch (expected: IllegalArgumentException) {
            assertEquals("WORKSPACE_PATH_OUTSIDE_ROOT", expected.message)
        }
    }

}
