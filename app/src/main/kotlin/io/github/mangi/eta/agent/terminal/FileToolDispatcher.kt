package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.agent.tool.requiredText
import org.json.JSONObject

internal object FileToolDispatcher {
    fun execute(backend: FileToolBackend, name: String, args: JSONObject): String {
        val operations = AgentFileOperations(backend)
        val path = if (args.has("path")) args.requiredText("path") else ""
        if ("://" in path) throw FileToolException("UNSUPPORTED_FILE_REFERENCE", "请使用已授权的文件系统路径；文档 URI 需先导入，导入路径对应副本")
        val revision = args.optString("expected_revision").takeIf(String::isNotBlank)
        val result = when (name) {
            "stat_file" -> operations.statFile(args.requiredText("path"))
            "read_file" -> operations.readFile(
                path = args.requiredText("path"),
                offsetBytes = args.optLong("offset_bytes", 0),
                maxBytes = args.optInt("max_bytes", FileToolReadLimits.MAX_BYTES),
                startLine = if (args.has("start_line")) args.getInt("start_line") else null,
                maxLines = args.optInt("max_lines", FileToolReadLimits.MAX_LINES),
                expectedRevision = revision,
            )
            "write_file" -> operations.writeFile(args.requiredText("path"), args.requiredText("content"), args.optBoolean("append", false), revision)
            "edit_file" -> operations.editFile(args.requiredText("path"), args.requiredText("old_text"), args.requiredText("new_text"), args.optBoolean("replace_all", false), revision)
            "list_directory" -> operations.listDirectory(path, args.optBoolean("show_hidden", false), args.optInt("limit", 80), args.optInt("offset", 0), revision)
            "glob_files" -> operations.globFiles(path, args.requiredText("pattern"), args.optInt("limit", 80), args.optString("cursor").takeIf(String::isNotEmpty), args.optBoolean("include_hidden", false))
            "grep_files" -> operations.grepFiles(path, args.requiredText("query"), args.optString("glob", "**/*"), args.optInt("limit", 50), args.optString("cursor").takeIf(String::isNotEmpty), args.optBoolean("include_hidden", false), args.optBoolean("case_sensitive", true))
            else -> throw FileToolException("UNKNOWN_TOOL", "未知文件工具")
        }
        val json = JSONObject(result)
        if (!json.optBoolean("ok") && json.optString("code") in setOf("FILE_NOT_FOUND", "NOT_DIRECTORY", "FILE_ACCESS_DENIED")) {
            pathEnvironmentHint(path.ifEmpty { args.optString("cwd") }, backend.environment)?.let { hint ->
                json.put("environment_hint", hint)
            }
        }
        return json.toString()
    }

    /** Diagnostics only: never reinterpret symlinks, guest paths, mount subtrees or identity. */
    internal fun pathEnvironmentHint(path: String, environment: String): String? {
        fun under(root: String) = path == root || path.startsWith("$root/")
        return when {
            environment == "android" && under("/workspace") ->
                "这是 Linux 路径；若 Linux 已就绪，请传 environment=linux 并使用同一路径。/workspace/mounts/<name> 只在 Linux 挂载命名空间中可见；不要在宿主 rootfs 下查找。chroot 的 /workspace 对应 Android /data/local/tmp/eta，PRoot 则对应本次运行的私有工作区。"
            environment == "linux" && (under("/data/data") || under("/data/user")) ->
                "这是 Android 宿主路径；请传 environment=android 并使用原路径及已授权身份。不要把宿主 rootfs 路径当作 Linux guest 路径。"
            environment == "linux" && under("/data/local/tmp/eta") ->
                "这是 Android 宿主工作区路径；chroot 中可用 /workspace 访问同一物理目录。PRoot 的 /workspace 是 App 私有工作区，不能假定两者相同；可用 environment=android 访问原路径。"
            environment == "linux" && (under("/sdcard") || under("/storage/emulated/0")) ->
                "共享存储在两端是同一物理目录；Linux 中优先使用 /storage/emulated/0（/sdcard 别名未必存在），并确认共享存储授权与挂载；也可传 environment=android 使用原路径。"
            else -> null
        }
    }
}
