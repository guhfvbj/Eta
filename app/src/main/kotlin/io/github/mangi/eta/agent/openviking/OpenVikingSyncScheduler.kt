package io.github.mangi.eta.agent.openviking

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PersistableBundle
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.OpenVikingSettings
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.OpenVikingCredentialState
import io.github.mangi.eta.data.repository.OpenVikingSecretStore
import io.github.mangi.eta.data.repository.OpenVikingSettingsRepository
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One persisted, network-constrained job; no override deadline can bypass connectivity.
 * Android may delay execution for quota/Doze. Manual Sync now remains an explicit foreground action. */
internal object OpenVikingSyncScheduler {
    const val JOB_ID = 1112
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val running = CopyOnWriteArraySet<OpenVikingCancellationSource>()
    fun register(source: OpenVikingCancellationSource): AutoCloseable {
        running.add(source)
        return AutoCloseable { running.remove(source) }
    }
    fun cancel(context: Context) {
        running.forEach(OpenVikingCancellationSource::cancel)
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }
    fun request(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        scope.launch { runCatching { refresh(app, force) }.onFailure(::warn) }
    }
    suspend fun refresh(context: Context, force: Boolean = false) = lock.withLock {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return@withLock
        val s = OpenVikingSettingsRepository.settings()
        val connection = runCatching { s.validate(); s.connectionId() }.getOrNull()
        if (!s.enabled || !AgentMemoryRepository.isEnabled() || connection == null ||
            OpenVikingSecretStore(context).credential(s.secretRevision) !is OpenVikingCredentialState.Available) {
            cancel(context); return@withLock
        }
        val now = System.currentTimeMillis()
        val due = nextDue(EtaDatabase.get(context), s, now, force)
        if (due == null) { scheduler.cancel(JOB_ID); return@withLock }
        val prior = scheduler.getPendingJob(JOB_ID)
        if (prior?.extras?.getString("connection") == connection && prior.extras.getLong("due_at", Long.MAX_VALUE) <= due &&
            (!force || prior.extras.getBoolean("force"))) return@withLock
        val extras = PersistableBundle().apply {
            putString("connection", connection); putLong("due_at", due); putBoolean("force", force)
        }
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, OpenVikingSyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setMinimumLatency((due - now).coerceAtLeast(0))
            .setPersisted(true).setExtras(extras).setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
        if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) AndroidAgentLogger.warn("OpenViking sync scheduling unavailable")
    }

    internal suspend fun nextDue(db: EtaDatabase, s: OpenVikingSettings, now: Long, force: Boolean = false): Long? {
        val connection = s.connectionId() ?: return null
        val dao = db.openVikingDao()
        dao.deleteExpiredCaptureDrafts(connection, now)
        val state = dao.syncState(connection)
        if (state?.pausedReason != null) return null
        val ops = dao.activeOperations(connection)
        val candidates = ops.filter { it.state == "POLLING" }.map { maxOf(now + 1_000, it.nextAttemptAt) }.toMutableList()
        // After process death a SENDING operation may be the only remaining work. Wake after
        // its lease expires so recoverSending quarantines it instead of silently abandoning it.
        if (ops.any { it.state == "SENDING" }) candidates += maxOf(now + 1_000, (state?.leaseUntil ?: 0) + 1_000)
        if (ops.any { it.state == "UNCERTAIN" }) return candidates.minOrNull()
        val retry = ops.filter { it.state == "RETRY" }
        if (retry.isNotEmpty()) return (candidates + retry.map { maxOf(now + 1_000, it.nextAttemptAt) }).minOrNull()
        val availableAt = maxOf(now + 1_000, (state?.leaseUntil ?: 0) + 1_000)
        for (session in dao.sessions(connection)) {
            if (dao.pending(session.id).isNotEmpty()) candidates += availableAt
            if (session.ackedSeq <= session.archivedSeq) continue
            val priority = force || dao.explicitPending(session.id, session.archivedSeq) > 0
            val due = priority ||
                dao.pendingTokens(session.id, session.archivedSeq) >= s.commitTokenThreshold ||
                dao.pendingTurns(session.id, session.archivedSeq) >= s.commitTurnThreshold
            val interval = session.lastCommitAt + s.minCommitIntervalSeconds * 1000L
            candidates += if (priority) availableAt else maxOf(availableAt, interval,
                if (due) now else session.lastAppendAt + s.idleCommitMinutes * 60_000L)
        }
        return candidates.minOrNull()
    }

    fun networkAllowed(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
    private fun warn(failure: Throwable) = AndroidAgentLogger.warnThrottled("openviking_schedule") {
        "OpenViking scheduling unavailable: type=${failure.safeLogType()}"
    }
}
