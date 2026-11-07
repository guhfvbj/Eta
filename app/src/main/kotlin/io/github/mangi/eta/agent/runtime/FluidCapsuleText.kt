package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.tool.AgentToolCapabilities

internal enum class FluidCapsuleState { RUNNING, IDLE, PAUSED, CONFIRM, TAKEN_OVER, SUSPENDED }

/**
 * ColorOS 的短文案按字符宽度决定胶囊长度。以原有正常态「处理中」为基准，
 * 各状态统一为三个全宽汉字；工具名、步骤与数字进度只放在展开卡片。
 * 不使用空格补齐（系统可能 trim），也不改变其他系统的实时活动文案。
 */
internal object FluidCapsuleText {
    fun text(state: FluidCapsuleState, original: String,
        colorOs: Boolean = AgentToolCapabilities.isColorOsDevice()): String {
        if (!colorOs) return original
        return when (state) {
            FluidCapsuleState.RUNNING -> "处理中"
            FluidCapsuleState.IDLE -> "空闲中"
            FluidCapsuleState.PAUSED -> "已暂停"
            FluidCapsuleState.CONFIRM -> "待确认"
            FluidCapsuleState.TAKEN_OVER -> "接管中"
            FluidCapsuleState.SUSPENDED -> "已挂起"
        }
    }
}
