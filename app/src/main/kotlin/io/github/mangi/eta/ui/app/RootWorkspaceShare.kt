package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import java.io.File
import java.util.UUID

/** The protected host tree is never made directly readable by FileProvider. */
internal fun isProtectedWorkspacePath(path: String): Boolean =
    RootAccess.isGranted && File(path).isAbsolute && File(path).toPath().normalize().startsWith(
        File(TerminalRuntime.ROOT_WORKSPACE_PATH).toPath(),
    )

/** Stage an exact binary copy, not a UTF-8 preview. No write-back to the original is implied. */
internal suspend fun stageProtectedWorkspaceFile(context: Context, path: String): File {
    require(isProtectedWorkspacePath(path)) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
    val root = File(TerminalRuntime.ROOT_WORKSPACE_PATH)
    val relative = File(path).toPath().normalize().toFile().relativeTo(root).path
    val destination = File(context.cacheDir, "shared_docs/${UUID.randomUUID()}/${File(path).name}")
    WorkspaceFileStore(context, rootDirectory = root).copyTo(relative, destination)
    return destination
}
