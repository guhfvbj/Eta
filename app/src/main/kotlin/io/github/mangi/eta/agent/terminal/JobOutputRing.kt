package io.github.mangi.eta.agent.terminal

/** UTF-8 字节游标；淘汰只改变 earliest，不重置绝对偏移。所有快照与推进均串行化。 */
internal class JobOutputRing(
    private val capacity: Int = 256 * 1024,
    initialOffset: Long = 0,
) {
    init { require(capacity >= 0 && initialOffset >= 0) }

    data class Cursor(var offset: Long = 0)
    data class Read(
        val text: String,
        val nextOffset: Long,
        val lossy: Boolean,
        val totalBytes: Long,
        val gapBefore: Boolean = false,
    )

    private data class Chunk(var at: Long, var bytes: ByteArray, var gapBefore: Boolean = false)
    private val chunks = ArrayDeque<Chunk>()
    @Volatile var total: Long = initialOffset
        private set
    @Volatile var earliest: Long = initialOffset
        private set
    @Volatile var retainedBytes: Int = 0
        private set

    @Synchronized
    fun append(text: String): Boolean = append(text.toByteArray(Charsets.UTF_8))

    /** 原始流可能在字符中间分块；读取时才解码，未完整的尾字符留给下次读取。 */
    @Synchronized
    fun append(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        chunks.addLast(Chunk(total, bytes.copyOf()))
        total += bytes.size
        retainedBytes += bytes.size
        while (retainedBytes > capacity && chunks.size > 1) {
            retainedBytes -= chunks.removeFirst().bytes.size
        }
        while (chunks.isNotEmpty()) {
            val first = chunks.first()
            val start = first.bytes.utf8Start((first.bytes.size - capacity).coerceAtLeast(0))
            if (start == 0) break
            first.at += start
            first.bytes = first.bytes.copyOfRange(start, first.bytes.size)
            first.gapBefore = true
            retainedBytes -= start
            if (first.bytes.isNotEmpty()) break
            chunks.removeFirst()
        }
        earliest = chunks.firstOrNull()?.at ?: total
        return true
    }

    @Synchronized
    fun readFrom(fromOffset: Long, maxBytes: Int = Int.MAX_VALUE): Read {
        require(maxBytes >= 0)
        val bytes = ByteArray(retainedBytes)
        var position = 0
        chunks.forEach { chunk ->
            chunk.bytes.copyInto(bytes, position)
            position += chunk.bytes.size
        }
        val start = bytes.utf8Start((fromOffset - earliest).coerceIn(0, bytes.size.toLong()).toInt())
        val end = bytes.utf8End((start.toLong() + maxBytes).coerceAtMost(bytes.size.toLong()).toInt())
            .coerceAtLeast(start)
        return Read(
            text = bytes.decodeToString(start, end),
            nextOffset = earliest + end,
            lossy = fromOffset < earliest,
            totalBytes = total,
            gapBefore = fromOffset < earliest || chunks.firstOrNull()?.gapBefore == true,
        )
    }

    @Synchronized
    fun consumeFrom(cursor: Cursor, maxBytes: Int = Int.MAX_VALUE): Read =
        readFrom(cursor.offset, maxBytes).also { cursor.offset = it.nextOffset }
}

/** 向右跳过 UTF-8 续字节，防止尾部预览从一个字符的中间开始。 */
internal fun ByteArray.utf8Start(from: Int = 0): Int {
    var start = from.coerceIn(0, size)
    while (start < size && (this[start].toInt() and 0xC0) == 0x80) start++
    return start
}

/** 有界头部或尚未排空的流不解码最后一个不完整字符；原始 bytes() 仍可用于二进制文件。 */
internal fun ByteArray.utf8End(until: Int = size): Int {
    val end = until.coerceIn(0, size)
    if (end == 0) return 0
    var lead = end - 1
    while (lead > 0 && (this[lead].toInt() and 0xC0) == 0x80) lead--
    val byte = this[lead].toInt() and 0xFF
    val width = when {
        byte in 0xC2..0xDF -> 2
        byte in 0xE0..0xEF -> 3
        byte in 0xF0..0xF4 -> 4
        else -> 1
    }
    return if (lead + width > end) lead else end
}

internal fun ByteArray.decodeUtf8Safe(): String = decodeToString(0, utf8End())
