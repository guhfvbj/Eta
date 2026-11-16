package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.terminal.DetachedTask
import io.github.mangi.eta.agent.terminal.JobCompletionSink
import io.github.mangi.eta.agent.terminal.JobDelivery
import io.github.mangi.eta.data.db.EtaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** 终端宿主与 Runtime 间的串行决策边界；失败统一交给通知兜底。 */
internal class AgentJobWakeCoordinator(
    context: Context? = null,
    private val conversationExists: (String) -> Boolean = { id ->
        runBlocking(Dispatchers.IO) { EtaDatabase.get(requireNotNull(context)).conversationDao().conversationCount(id) > 0 }
    },
    injectIntoActiveRun: ((conversationId: String, notice: String) -> Boolean)? = null,
    wake: ((AgentContinuationBaseStore.Base, notice: String) -> Boolean)? = null,
    private val loadBase: (String) -> AgentContinuationBaseStore.Base? = { id ->
        AgentContinuationBaseStore.default(requireNotNull(context)).load(id)
    },
    private val wakeBudget: WakeBudget = PersistentWakeBudget(context),
    private val notice: (DetachedTask) -> String = AgentJobNotice::build,
    private val removeBase: (String) -> Unit = { id ->
        AgentContinuationBaseStore.default(requireNotNull(context)).remove(id)
    },
) : JobCompletionSink {
    interface WakeBudget {
        fun tryConsume(conversationId: String): Boolean
    }

    private var currentJobId = ""
    private val injectIntoActiveRun = injectIntoActiveRun ?: { id: String, text: String ->
        AgentRuntimeService.deliverToActiveRun(id, text, currentJobId)
    }
    private val wake = wake ?: { base: AgentContinuationBaseStore.Base, text: String ->
        AgentRuntimeService.wakeForJobCompletion(requireNotNull(context), base, text, currentJobId)
    }
    // 宿主落盘前的同一快照也可能重入；成功结果返回 true，避免重复降级通知。
    private val settlements = linkedMapOf<String, Boolean>()

    @Synchronized
    override fun onJobCompleted(task: DetachedTask): Boolean = runCatching {
        val conversationId = task.conversationId
        if (task.originRunId == null || conversationId == null) return false
        if (task.delivery == JobDelivery.INLINE) return false
        if (task.consumedAt != null || task.deliveryAttemptedAt != null) return false
        if (!conversationExists(conversationId)) {
            removeBase(conversationId)
            return false
        }
        if (task.delivery == JobDelivery.QUIET) return false
        settlements[task.id]?.let { return it }
        // 先登记尝试；即使外部副作用抛错，也不重复执行同一任务。
        settlements[task.id] = false
        currentJobId = task.id
        val text = notice(task)
        val handled = when {
            injectIntoActiveRun(conversationId, text) -> true
            !wakeBudget.tryConsume(conversationId) -> false
            else -> loadBase(conversationId)?.let { wake(it, text) } ?: false
        }
        settlements[task.id] = handled
        handled
    }.getOrDefault(false)

    private class PersistentWakeBudget(private val context: Context?) : WakeBudget {
        override fun tryConsume(conversationId: String): Boolean = synchronized(BUDGET_LOCK) {
            val app = requireNotNull(context)
            if (AgentConversationRuntimeStore.load(app, conversationId).consecutiveJobWakes >= MAX_CONSECUTIVE_JOB_WAKES) false
            else {
                AgentConversationRuntimeStore.incrementJobWakes(app, conversationId, MAX_CONSECUTIVE_JOB_WAKES)
                true
            }
        }
    }

    companion object {
        const val MAX_CONSECUTIVE_JOB_WAKES = 3
        private val BUDGET_LOCK = Any()
    }
}
