package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test

class FluidCapsuleTextTest {
    @Test fun allColorOsStatesKeepTheExistingNormalTextWidth() {
        val normal = FluidCapsuleText.text(FluidCapsuleState.RUNNING, "处理中", colorOs = true)
        assertEquals("处理中", normal)
        FluidCapsuleState.entries.forEach { state ->
            val text = FluidCapsuleText.text(state, "任意很长的进度 123/456", colorOs = true)
            assertEquals(state.name, normal.length, text.length)
            assertTrue(text.all { it in '\u4e00'..'\u9fff' })
        }
    }
    @Test fun toolNamesAndIncreasingProgressCannotStretchColorOsCapsules() {
        listOf("read_file", "执行很长的工具名", "正在比价 1/5", "正在比价 123/10000").forEach { detail ->
            assertEquals("处理中", FluidCapsuleText.text(FluidCapsuleState.RUNNING, detail, colorOs = true))
        }
    }
    @Test fun otherPlatformsKeepTheirOriginalLiveActivityLabels() {
        FluidCapsuleState.entries.forEach { state ->
            val original = "原有文案 ${state.name} 2/5"
            assertEquals(original, FluidCapsuleText.text(state, original, colorOs = false))
        }
    }
}
