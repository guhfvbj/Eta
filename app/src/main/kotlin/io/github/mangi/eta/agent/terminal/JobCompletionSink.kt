package io.github.mangi.eta.agent.terminal

internal sealed interface JobCompletionOutcome {
    /** 已成功接管（运行中注入或唤醒新回合）。 */
    data object Handled : JobCompletionOutcome

    /** 未能接管，调用方应降级发通知，并附带可选简短原因。 */
    data class Fallback(val reason: String? = null) : JobCompletionOutcome
}

internal interface JobCompletionSink {
    /** @return true 表示已接管（运行中注入或唤醒新回合）；false 表示调用方必须降级为通知。 */
    fun onJobCompleted(task: DetachedTask): Boolean

    fun onJobCompletedWithReason(task: DetachedTask): JobCompletionOutcome =
        if (onJobCompleted(task)) JobCompletionOutcome.Handled else JobCompletionOutcome.Fallback()
}
