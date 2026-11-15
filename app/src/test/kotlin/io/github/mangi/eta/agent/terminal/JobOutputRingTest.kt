package io.github.mangi.eta.agent.terminal

import org.junit.Assert.*
import org.junit.Test

class JobOutputRingTest {
    @Test fun evictingChunksPreservesAbsoluteOffsetsAndReportsLoss() {
        val ring = JobOutputRing(8)
        ring.append("ab中")
        ring.append("😀")
        ring.append("z")
        assertEquals(10L, ring.total)
        assertEquals(5L, ring.earliest)
        assertEquals(5, ring.retainedBytes)
        val read = ring.readFrom(0)
        assertEquals("😀z", read.text)
        assertEquals(10L, read.nextOffset)
        assertEquals(10L, read.totalBytes)
        assertTrue(read.lossy)
        assertFalse(ring.readFrom(5).lossy)
    }

    @Test fun oversizedChunkKeepsUtf8SafeTailAndGapMarker() {
        val ring = JobOutputRing(7)
        ring.append("A你😀好Z")
        assertEquals(12L, ring.total)
        assertEquals(8L, ring.earliest)
        assertEquals(4, ring.retainedBytes)
        val read = ring.readFrom(0)
        assertEquals("好Z", read.text)
        assertTrue(read.lossy)
        assertTrue(read.gapBefore)
        assertEquals(12L, read.nextOffset)
    }

    @Test fun readsAreNonConsumingAndCursorAdvancesOnlyByReturnedBytes() {
        val ring = JobOutputRing()
        val cursor = JobOutputRing.Cursor()
        ring.append("a中😀b")
        assertEquals("a中", ring.consumeFrom(cursor, 5).text)
        assertEquals(4L, cursor.offset)
        assertEquals("😀b", ring.consumeFrom(cursor).text)
        assertEquals(9L, cursor.offset)
        assertEquals("", ring.consumeFrom(cursor).text)
        assertEquals("a中😀b", ring.readFrom(0).text)
        assertEquals("😀b", ring.readFrom(2).text)
        assertFalse(ring.readFrom(2).lossy)
        ring.append("好")
        assertEquals("好", ring.consumeFrom(cursor).text)
        assertEquals(12L, cursor.offset)
    }

    @Test fun streamingChunksDoNotDecodeIncompleteCharactersOrLoseTheirCursor() {
        val ring = JobOutputRing()
        val bytes = "你😀".toByteArray()
        val cursor = JobOutputRing.Cursor()
        ring.append(bytes.copyOfRange(0, 1))
        assertEquals("", ring.consumeFrom(cursor).text)
        assertEquals(0L, cursor.offset)
        assertEquals(1L, ring.total)
        ring.append(bytes.copyOfRange(1, 5))
        assertEquals("你", ring.consumeFrom(cursor).text)
        assertEquals(3L, cursor.offset)
        ring.append(bytes.copyOfRange(5, 7))
        assertEquals("😀", ring.consumeFrom(cursor).text)
        assertEquals(7L, cursor.offset)
    }

    @Test fun zeroCapacityAndLargeStartingOffsetsRemainBounded() {
        val empty = JobOutputRing(0)
        assertFalse(empty.append(""))
        empty.append("你")
        assertEquals(3L, empty.total)
        assertEquals(3L, empty.earliest)
        assertEquals(0, empty.retainedBytes)
        assertTrue(empty.readFrom(0).lossy)
        val base = Int.MAX_VALUE.toLong() + 100
        val ring = JobOutputRing(4, base)
        ring.append("😀")
        assertEquals(base + 4, ring.readFrom(base).nextOffset)
        assertFalse(ring.readFrom(base).lossy)
    }

    @Test fun everySmallCapacityKeepsWholeUtf8Characters() {
        for (capacity in 0..16) {
            val ring = JobOutputRing(capacity)
            var total = 0L
            listOf("a你😀", "好", "z😀中", "你😀好").forEach { text ->
                total += text.toByteArray().size
                ring.append(text)
                val read = ring.readFrom(0)
                assertEquals(total, ring.total)
                assertEquals(total - ring.retainedBytes, ring.earliest)
                assertTrue(ring.retainedBytes <= capacity)
                assertFalse(read.text.contains('\uFFFD'))
                assertEquals(total, read.nextOffset)
                assertEquals(read.text.toByteArray().size, ring.retainedBytes)
            }
        }
    }

    @Test fun boundedCollectorDropsPartialUtf8TailButKeepsRawBinaryBytes() {
        val collector = ByteArrayOutputCollector()
        collector.readFrom("ab你😀".byteInputStream(), 4)
        assertEquals("ab", collector.text())
        assertEquals(4, collector.bytes().size)
        assertEquals(9L, collector.totalBytesRead())
        assertTrue(collector.isTruncated())
    }
}
