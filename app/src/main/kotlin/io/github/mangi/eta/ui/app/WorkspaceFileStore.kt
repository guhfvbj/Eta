package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class WorkspaceEntry(val path: String, val name: String, val directory: Boolean, val size: Long)

internal const val WORKSPACE_PREVIEW_MAX_BYTES: Long = 2L * 1024 * 1024

internal class WorkspaceFileStore(
    context: Context,
    rootDirectory: File? = null,
    private val directAccess: (File) -> Boolean = { it.canRead() },
    private val backendFor: (File) -> FileToolBackend = ::workspaceBackend,
) {
    private val appContext = context.applicationContext
    // /data/adb remains private to Root. Never relax its mode for the file browser.
    private val root: File = (rootDirectory ?: if (RootAccess.isGranted) {
        File(TerminalRuntime.ROOT_WORKSPACE_PATH)
    } else {
        TerminalPrivateStorage.workspace(appContext.filesDir).apply { mkdirs() }
    }).let { if (it.canRead()) it.canonicalFile else it.absoluteFile.toPath().normalize().toFile() }
    private val backend: FileToolBackend by lazy { backendFor(root) }

    suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        val directory = resolveFile(path)
        val entries = if (directAccess(directory)) {
            if (!directory.isDirectory) throw java.io.IOException("WORKSPACE_DIRECTORY_UNAVAILABLE")
            val children = directory.listFiles() ?: throw java.io.IOException("WORKSPACE_DIRECTORY_UNREADABLE")
            children.asSequence()
                .filter { !Files.isSymbolicLink(it.toPath()) && (it.isFile || it.isDirectory) }
                .map { WorkspaceEntry(it.relativeTo(root).path, it.name, it.isDirectory, if (it.isDirectory) 0L else it.length()) }
                .toList()
        } else {
            val source = resolve(backend, path)
            val result = mutableListOf<WorkspaceEntry>()
            var offset = 0
            var revision: String? = null
            do {
                ensureActive()
                val page = backend.list(source, offset, 200, revision)
                page.entries.filter { it.kind == FileToolKind.FILE || it.kind == FileToolKind.DIRECTORY }.forEach {
                    require(File(it.path).toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
                    result += WorkspaceEntry(File(it.path).relativeTo(root).path, it.name, it.kind == FileToolKind.DIRECTORY, it.sizeBytes)
                }
                if (!page.hasMore) break
                check(page.nextOffset > offset) { "WORKSPACE_DIRECTORY_CHANGED" }
                offset = page.nextOffset
                revision = page.revision
            } while (true)
            result
        }
        entries.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun absolutePath(path: String): String = resolveFile(path).absolutePath

    private fun resolveFile(path: String): File {
        val candidate = if (path.isBlank()) root else File(root, path)
        // App-side realpath may fail at /data/adb (0700); privileged resolve rechecks links.
        val canonical = if (directAccess(candidate)) candidate.canonicalFile else candidate.absoluteFile.toPath().normalize().toFile()
        require(canonical.toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        return canonical
    }

    suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        copyContents(path, output, WORKSPACE_PREVIEW_MAX_BYTES)
        output.toString(Charsets.UTF_8.name())
    }

    /** Binary-safe staging has no text-preview size limit. Partial copies are never shared. */
    suspend fun copyTo(path: String, destination: File): Long = withContext(Dispatchers.IO) {
        require(destination.canonicalFile != resolveFile(path)) { "WORKSPACE_COPY_TO_SOURCE" }
        destination.parentFile?.mkdirs()
        try {
            destination.outputStream().use { copyContents(path, it) }
            destination.length()
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    suspend fun importFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val imported = AgentFileReferenceGateway.importDocumentUri(appContext, uri)
        if (imported !is AgentFileReferenceGateway.Resolution.Success) return@withContext false
        if (root == TerminalPrivateStorage.workspace(appContext.filesDir).canonicalFile) return@withContext true
        val source = File(imported.reference.absolutePath)
        val relative = "imports/${source.parentFile!!.name}/${source.name}"
        val target = resolve(backend, relative)
        source.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            var append = false
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                backend.write(target, buffer.copyOf(count), append)
                append = true
            }
            if (!append) backend.write(target, byteArrayOf(), false)
        }
        true
    }

    suspend fun exportFile(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val output = appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
        output.use { copyContents(path, it) }
    }

    private suspend fun copyContents(path: String, output: OutputStream, maxBytes: Long = Long.MAX_VALUE) {
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        check(stat.kind == FileToolKind.FILE) { "WORKSPACE_NOT_FILE" }
        if (stat.sizeBytes > maxBytes) throw java.io.IOException("WORKSPACE_FILE_TOO_LARGE")
        val file = File(source)
        if (directAccess(file)) {
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= maxBytes) { "WORKSPACE_FILE_TOO_LARGE" }
                    output.write(buffer, 0, count)
                }
                check(total == stat.sizeBytes) { "WORKSPACE_FILE_CHANGED" }
            }
        } else {
            var offset = 0L
            while (offset < stat.sizeBytes) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val chunkSize = if (backend is ShellFileToolBackend) 256 * 1024 else 16 * 1024
                val limit = minOf(chunkSize.toLong(), stat.sizeBytes - offset).toInt()
                val bytes = backend.readBytes(source, offset, limit)
                check(bytes.isNotEmpty() && bytes.size <= limit) { "WORKSPACE_FILE_CHANGED" }
                output.write(bytes)
                offset += bytes.size
            }
        }
        check(backend.stat(source).revision == stat.revision) { "WORKSPACE_FILE_CHANGED" }
    }

    private fun resolve(backend: FileToolBackend, path: String): String = backend.resolve(path).also {
        require(File(it).toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
    }
}

private fun workspaceBackend(root: File): FileToolBackend {
    if (!RootAccess.isGranted) return LocalFileToolBackend(root)
    val supervisor = ShellProcessSupervisor()
    return ShellFileToolBackend("android", "root", root.path, root.path) { command, input, limit ->
        runOneShotShell(supervisor, "root", command, timeoutSeconds = 20, stdin = input, maxOutputBytes = limit)
    }
}

internal suspend fun readWorkspaceText(store: WorkspaceFileStore, relativePath: String): String? =
    try {
        store.readText(relativePath)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
