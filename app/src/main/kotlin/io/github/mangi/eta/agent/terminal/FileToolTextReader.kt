package io.github.mangi.eta.agent.terminal

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Model-facing read budgets; directory/search/edit budgets are independent. */
internal object FileToolReadLimits {
    const val MAX_BYTES = 50 * 1024
    const val MAX_LINES = 2_000
    const val METADATA_SCAN_BYTES = 256 * 1024
}

internal data class FileToolTextChunk(
    val text: String,
    val bytesConsumed: Int,
    val eof: Boolean,
)

/** 字节游标只能越过已完整解码的字符，不把半个 UTF-8 字符记为已读。 */
internal class FileToolTextReader(private val backend: FileToolBackend) {
    /**
     * Editing never round-trips a lossy String. Validate the whole bounded file,
     * then return the original bytes for exact UTF-8 byte matching/splicing.
     * NUL is always binary; malformed bytes and non-whitespace C0/DEL bytes may
     * together occupy at most 5% of the file. This is not a legacy codec guess.
     */
    fun readEditableBytes(stat: FileToolStat): ByteArray {
        if (stat.sizeBytes > 512 * 1024) throw FileToolException("FILE_TOO_LARGE", "精确编辑仅支持不超过 512 KiB 的文本文件")
        val buffer = ByteArrayOutputStream(stat.sizeBytes.toInt())
        while (buffer.size().toLong() < stat.sizeBytes) {
            backend.checkActive()
            val bytes = backend.readBytes(stat.path, buffer.size().toLong(), minOf(16_000L, stat.sizeBytes - buffer.size()).toInt())
            if (bytes.isEmpty()) throw FileToolException("FILE_CHANGED", "文件在读取期间已变化")
            buffer.write(bytes)
        }
        val bytes = buffer.toByteArray()
        var suspicious = bytes.count { byte ->
            val value = byte.toInt() and 0xff
            if (value == 0) throw FileToolException("BINARY_FILE", "文件包含 NUL 字节，不能作为文本编辑")
            value == 127 || (value < 32 && value !in listOf(9, 10, 12, 13))
        }
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(4096)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        while (true) {
            backend.checkActive()
            val result = decoder.decode(input, output, true)
            if (result.isError) {
                suspicious += result.length()
                input.position(input.position() + result.length())
            }
            output.clear()
            if (suspicious.toLong() * 20 > bytes.size) {
                throw FileToolException("BINARY_FILE", "异常编码或控制字节超过 5%，不能安全地作为文本编辑")
            }
            if (result.isUnderflow) break
        }
        return bytes
    }

    fun read(stat: FileToolStat, offset: Long, maxBytes: Int, maxChars: Int = FileToolReadLimits.MAX_BYTES): FileToolTextChunk {
        return decode(stat, offset, backend.readBytes(stat.path, offset, maxBytes.coerceIn(1, FileToolReadLimits.MAX_BYTES)), maxChars)
    }

    /** Decode a checked snapshot; callers such as search retain their own smaller byte budgets. */
    fun decode(stat: FileToolStat, offset: Long, bytes: ByteArray, maxChars: Int = FileToolReadLimits.MAX_BYTES): FileToolTextChunk {
        if (bytes.any { it == 0.toByte() }) throw FileToolException("BINARY_FILE", "文件包含二进制内容，请使用文件导出或终端工具处理")
        val eof = offset + bytes.size >= stat.sizeBytes
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(minOf(maxChars.coerceIn(2, FileToolReadLimits.MAX_BYTES), bytes.size.coerceAtLeast(2)))
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(input, output, eof)
        if (result.isError) throw FileToolException("NOT_UTF8_TEXT", "内容不是有效 UTF-8 文本，或字节游标位于字符中间")
        if (bytes.isNotEmpty() && input.position() == 0) {
            throw FileToolException("READ_LIMIT_TOO_SMALL", "字节上限不足以读取一个完整字符，请使用至少 4 字节")
        }
        if (bytes.isEmpty() && offset < stat.sizeBytes) throw FileToolException("FILE_CHANGED", "文件在读取期间已变化")
        output.flip()
        return FileToolTextChunk(output.toString(), input.position(), eof && input.position() == bytes.size)
    }
}
