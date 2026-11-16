package io.github.mangi.eta.agent.terminal

internal interface JobCompletionSink {
    /** @return true 表示已接管（运行中注入或唤醒新回合）；false 表示调用方必须降级为通知。 */
    fun onJobCompleted(task: DetachedTask): Boolean
}
