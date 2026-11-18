package io.github.mangi.eta.agent.openviking

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.OpenVikingCredentialState
import io.github.mangi.eta.data.repository.OpenVikingSecretStore
import io.github.mangi.eta.data.repository.OpenVikingSettingsRepository
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/** No model/runtime wakeup. Only clean encrypted outbox data crosses this service. */
internal class OpenVikingSyncJobService : JobService() {
    private var work: Thread? = null
    private var source: OpenVikingCancellationSource? = null
    private val stopped = AtomicBoolean()
    override fun onStartJob(params: JobParameters): Boolean {
        if (work?.isAlive == true) return false
        stopped.set(false)
        val cancellation = OpenVikingCancellationSource().also { source = it }
        val registration = OpenVikingSyncScheduler.register(cancellation)
        work = thread(name = "eta-openviking-sync") {
            val timeout = timer.schedule({ cancellation.cancel() }, 90, TimeUnit.SECONDS)
            var retry = false
            try { runBlocking {
                val settings = OpenVikingSettingsRepository.settings()
                val connection = settings.connectionId()
                if (!settings.enabled || connection == null || connection != params.extras.getString("connection") ||
                    !AgentMemoryRepository.isEnabled() || !OpenVikingSyncScheduler.networkAllowed(this@OpenVikingSyncJobService)) return@runBlocking
                val secrets = OpenVikingSecretStore(this@OpenVikingSyncJobService)
                val credential = secrets.credential(settings.secretRevision) as? OpenVikingCredentialState.Available ?: return@runBlocking
                val client = OpenVikingHttpClient(settings.baseUrl, credential.apiKey, settings.actorPeer,
                    settings.foregroundDeadlineMs.toLong(), cancellation)
                // Recheck the saved identity/switches and connectivity before EVERY ordered call.
                val guarded = object : OpenVikingSyncApi {
                    private fun check() = runBlocking {
                        val current = OpenVikingSettingsRepository.settings()
                        if (stopped.get() || !current.enabled || current.connectionId() != connection || !AgentMemoryRepository.isEnabled() ||
                            !OpenVikingSyncScheduler.networkAllowed(this@OpenVikingSyncJobService)) throw OpenVikingApiException(OpenVikingErrorCode.CANCELLED)
                    }
                    override fun createSession(request: CreateSessionRequest) { check(); client.createSession(request) }
                    override fun addMessages(sessionId: String, request: BatchAddMessageRequest) { check(); client.addMessages(sessionId, request) }
                    override fun commit(sessionId: String, request: CommitRequest): CommitResponse { check(); return client.commit(sessionId, request) }
                    override fun task(taskId: String): TaskStatusResponse { check(); return client.task(taskId) }
                }
                OpenVikingSyncCoordinator(OpenVikingOutboxRepository(EtaDatabase.get(this@OpenVikingSyncJobService), secrets))
                    .sync(settings, guarded, force = params.extras.getBoolean("force"))
            } } catch (failure: Exception) {
                retry = failure !is io.github.mangi.eta.data.repository.OpenVikingSecretsUnavailable
                AndroidAgentLogger.warnThrottled("openviking_sync_job") { "OpenViking sync unavailable: type=${failure.safeLogType()}" }
            } finally {
                timeout.cancel(false); registration.close()
                Handler(Looper.getMainLooper()).post {
                    if (!stopped.get()) jobFinished(params, retry)
                    // A settings save may cancel and immediately replace this job while the
                    // old worker is still stopping. Re-evaluate after its lease is released;
                    // disabled connections cancel here, enabled pending work gets a fresh job.
                    if (stopped.get() || !retry) OpenVikingSyncScheduler.request(this@OpenVikingSyncJobService)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        stopped.set(true); source?.cancel(); work?.interrupt()
        return true // JobScheduler restores connectivity/backoff constraints; uncertain writes stay quarantined.
    }
    override fun onDestroy() { stopped.set(true); source?.cancel(); work?.interrupt(); super.onDestroy() }
    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "eta-ov-sync-deadline").apply { isDaemon = true } }
    }
}
