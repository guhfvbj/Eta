package io.github.mangi.eta.agent.terminal

/** revision 是后端生成的不透明值，只在同一后端内比较；目录内容变化必须使其失效。 */
internal data class FileToolStat(
    val path: String,
    val name: String,
    val kind: FileToolKind,
    val sizeBytes: Long,
    val revision: String,
)

internal enum class FileToolKind(val wireName: String) {
    FILE("file"), DIRECTORY("directory"), SYMLINK("symlink"), OTHER("other"),
}

internal data class FileToolDirectoryPage(
    val entries: List<FileToolStat>,
    val nextOffset: Int,
    val hasMore: Boolean,
    val revision: String,
)

internal data class FileToolWriteResult(
    val stat: FileToolStat,
    val atomic: Boolean,
)

internal class FileToolException(val code: String, message: String) : Exception(message)

/** 参数中的路径均属于后端自身的命名空间；实现必须在每次操作时重新检查访问边界。 */
internal interface FileToolBackend {
    val environment: String
    val identity: String
    fun checkActive() = Unit
    fun resolve(path: String): String
    fun stat(path: String): FileToolStat
    fun readBytes(path: String, offset: Long, limit: Int): ByteArray

    /** offset 为目录原始枚举位置，不能在分页前过滤。顺序由后端保持，变动通过 revision 检测。 */
    fun list(path: String, offset: Int, limit: Int, expectedRevision: String? = null): FileToolDirectoryPage

    /**
     * expectedSha256 用于编辑时校验读到的完整旧内容；不匹配必须返回 FILE_CHANGED 且不写入。
     * expectedRevision 为可选元数据前置条件；atomic 只描述替换可见性，不承诺跨进程互斥。
     */
    fun write(
        path: String,
        bytes: ByteArray,
        append: Boolean,
        expectedRevision: String? = null,
        expectedSha256: String? = null,
    ): FileToolWriteResult
}

/** Optional, search-only snapshots. No shell matching: Kotlin retains all cursor/text semantics.
 * Each bounded read is checked before and after I/O. Implementations must not follow an
 * enumerated symlink or suppress per-file failures. Results are consumed in traversal order.
 */
internal interface FileToolSearchBackend {
    fun readSearchBatch(requests: List<FileToolSearchRequest>): List<Result<FileToolSearchRead>>

    /** Recheck consumed snapshots before publishing results (prefetch may outlive a read). */
    fun verifySearchBatch(stats: List<FileToolStat>)
}

internal data class FileToolSearchRequest(val path: String, val offset: Long, val limit: Int)
internal data class FileToolSearchRead(val stat: FileToolStat, val offset: Long, val bytes: ByteArray)
