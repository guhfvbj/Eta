package io.github.mangi.eta.agent.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentActivitySnapshotTest {
    @Test
    fun smallSnapshotsRoundTripWithoutChangingContent() {
        val snapshot = SubagentActivitySnapshot(subagentId = "sub_1", output = "结果😀")
        assertEquals(snapshot, SubagentActivitySnapshot.decode(snapshot.encode()))
    }

    @Test
    fun escapedFieldsCannotExceedTheSerializedBudget() {
        val control = 1.toChar().toString()
        val snapshot = SubagentActivitySnapshot(
            subagentId = "sub_3", status = SubagentActivitySnapshot.STATUS_COMPLETED,
            description = control.repeat(10_000), prompt = control.repeat(20_000),
            steps = List(48) { index -> SubagentStepSnapshot(
                id = "step-$index", kind = "tool", title = control.repeat(2_000),
                detail = control.repeat(2_000), toolName = "read_file",
            ) },
            output = control.repeat(12_000), error = control.repeat(1_000),
        )
        val encoded = snapshot.encode()
        assertTrue(encoded.length <= 48_000)
        val decoded = requireNotNull(SubagentActivitySnapshot.decode(encoded))
        assertEquals("sub_3", decoded.subagentId)
        assertEquals(SubagentActivitySnapshot.STATUS_COMPLETED, decoded.status)
        assertTrue(decoded.steps.isNotEmpty())
        assertTrue(decoded.output.isNotEmpty())
    }

    @Test
    fun compactionDoesNotSplitEmojiSurrogatePairs() {
        val snapshot = SubagentActivitySnapshot(
            subagentId = "sub_emoji",
            prompt = "x".repeat(1_999) + "😀" + "x".repeat(60_000),
            output = "x".repeat(11_999) + "😀" + "x".repeat(20_000),
        )
        val decoded = requireNotNull(SubagentActivitySnapshot.decode(snapshot.encode()))
        for (text in listOf(decoded.prompt, decoded.output)) {
            assertEquals(text, text.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        }
    }

    @Test
    fun largeSubagentActivitySnapshotStaysValidDecodableJson() {
        val steps = (1..48).map { i ->
            io.github.mangi.eta.agent.subagent.SubagentStepSnapshot(
                id = "tool-$i",
                kind = io.github.mangi.eta.agent.subagent.SubagentStepSnapshot.KIND_TOOL,
                title = "读取文件 $i",
                detail = "详".repeat(2_000),
                status = io.github.mangi.eta.agent.subagent.SubagentStepSnapshot.STATUS_SUCCESS,
                toolName = "read_file",
            )
        }
        val snapshot = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot(
            subagentId = "sub_3",
            subagentType = "explore",
            description = "调研架构",
            prompt = "请".repeat(4_000),
            model = "deepseek-v4.1-flash",
            effort = "high",
            status = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot.STATUS_COMPLETED,
            currentAction = "完成 · 48 次工具调用",
            steps = steps,
            output = "总".repeat(12_000),
        )
        val encoded = snapshot.encode()
        assertTrue(encoded.length <= 48_000)
        val decoded = io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot.decode(encoded)
        org.junit.Assert.assertNotNull(decoded)
        assertEquals("sub_3", decoded?.subagentId)
        assertEquals(io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot.STATUS_COMPLETED, decoded?.status)
        assertTrue(decoded!!.output.isNotBlank())
    }

}
