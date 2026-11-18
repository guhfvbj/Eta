package io.github.mangi.eta.agent.openviking

import android.content.Context
import io.github.mangi.eta.agent.model.AgentRequestContext
import io.github.mangi.eta.agent.model.providerMessageText
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.OpenVikingSettings
import io.github.mangi.eta.data.model.openVikingHash
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.OpenVikingCredentialState
import io.github.mangi.eta.data.repository.OpenVikingSecretStore
import io.github.mangi.eta.data.repository.OpenVikingSettingsRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray

/** One per top-level real-world run. Roleplay is rejected before any settings/key/network access. */
internal class OpenVikingRuntimeContext private constructor(
    private val context: Context,
    val settings: OpenVikingSettings,
    private val scope: String,
    private val controller: AgentRunController,
    private val skipRecallMessageId: String?,
) : AgentRequestContext, AutoCloseable {
    private val connection = checkNotNull(settings.connectionId())
    private val secrets = OpenVikingSecretStore(context)
    private val db = EtaDatabase.get(context)
    private val cache = OpenVikingContextCache(db, secrets)
    private val cancellation = OpenVikingCancellationSource()
    private val binding = controller.register(cancellation::cancel)
    private val profileCancellation = OpenVikingCancellationSource()
    private val profileBinding = controller.register(profileCancellation::cancel)
    private val syncRegistration = OpenVikingSyncScheduler.register(cancellation)
    private val profileRegistration = OpenVikingSyncScheduler.register(profileCancellation)
    private val resolved = mutableMapOf<Pair<Int, String>, String>()
    private val attempted = mutableSetOf<Pair<Int, String>>()
    private val turnBlocks = mutableMapOf<String, String>()
    @Volatile private var closed = false
    var hasInjected: Boolean = false
        private set

    fun startPrewarm() {
        if (!settings.profileInjection || settings.profileMaxTokens == 0 || !prewarming.add(connection)) return
        try {
            profileWorker.execute {
                try {
                    runBlocking {
                        if (cache.profile(connection) != null || !stillEnabled()) return@runBlocking
                        val credential = secrets.credential(settings.secretRevision) as? OpenVikingCredentialState.Available ?: return@runBlocking
                        // Profile is fetched separately and never delays the foreground recall. One bounded
                        // context request covers user profile/preferences/entities under the authenticated peer.
                        val profileSettings = settings.copy(autoRecall = true, recallMaxTokens = settings.profileMaxTokens.coerceAtLeast(64))
                        val client = OpenVikingHttpClient(settings.baseUrl, credential.apiKey, settings.actorPeer,
                            settings.foregroundDeadlineMs.toLong(), profileCancellation)
                        val text = client.contextSearch(OpenVikingRecallService.request(profileSettings,
                            "User profile, durable preferences, relationships and entities relevant to this user.")).injectableText()
                        cache.putProfile(connection, boundTokens(OpenVikingTextSafety.clean(text, credential.apiKey), settings.profileMaxTokens))
                    }
                } catch (failure: Exception) {
                    if (!closed && !controller.isCancelled) runCatching { runBlocking { cache.putProfile(connection, "") } }
                    warn("profile", failure)
                }
                finally { prewarming.remove(connection) }
            }
        } catch (failure: Exception) { prewarming.remove(connection); warn("profile", failure) }
    }

    override fun prepare(messages: JSONArray) {
        try {
            runBlocking {
                val users = (0 until messages.length()).map(messages::getJSONObject)
                    .filter { it.optString("role") == "user" && !it.optBoolean("_eta_observation") && !it.optBoolean("_eta_context_summary") }
                users.forEach { OpenVikingContextProjection.attach(it, "") }
                if (!stillEnabled()) return@runBlocking
                // The durable compaction marker also repairs a process death after snapshot commit
                // but before the completed-event callback. Normal snapshots carry the same marker.
                val compaction = (0 until messages.length()).map(messages::getJSONObject)
                    .filter { it.optBoolean("_eta_context_summary") }
                    .map { it.optString("_eta_compaction_epoch_id") }.lastOrNull(String::isNotBlank)
                if (compaction != null) cache.advance(connection, scope, compaction)
                val epoch = cache.epoch(connection, scope)
                val latest = users.lastOrNull()?.takeUnless {
                    it.optBoolean("_eta_openviking_skip_recall") || it.optString("_eta_message_id") == skipRecallMessageId
                }
                for (user in users) {
                    val id = user.optString("_eta_message_id")
                    if (id.isBlank()) continue
                    val key = epoch to id
                    val block = resolved[key] ?: cache.frozen(connection, scope, id, epoch) ?: if (user === latest && attempted.add(key)) {
                        resolved[key] = "" // No second request if local freezing fails.
                        freezeTurn(epoch, id, providerMessageText(user.opt("content")))
                    } else ""
                    resolved[key] = block
                    if (block.isNotBlank()) hasInjected = true
                    OpenVikingContextProjection.attach(user, block)
                }
            }
        } catch (failure: Exception) {
            for (index in 0 until messages.length()) messages.optJSONObject(index)?.let { OpenVikingContextProjection.attach(it, "") }
            warn("context", failure)
        }
    }

    private suspend fun freezeTurn(epoch: Int, id: String, query: String): String {
        val lock = turnLocks[Math.floorMod((connection + scope + id + epoch).hashCode(), turnLocks.size)]
        // Never spend the foreground deadline waiting for another run or a hash-stripe collision.
        if (!lock.tryLock()) return ""
        try {
            cache.frozen(connection, scope, id, epoch)?.let { return it }
            // Pressure compaction may run between preparation and the model call. A new epoch
            // creates a new frozen key without a second remote recall of the same live input.
            turnBlocks[id]?.let { return cache.freeze(connection, scope, id, epoch, it) }
            val credential = secrets.credential(settings.secretRevision) as? OpenVikingCredentialState.Available
            val profile = if (settings.profileInjection && settings.profileMaxTokens > 0)
                boundTokens(cache.profile(connection).orEmpty(), settings.profileMaxTokens) else ""
            val recall = if (credential != null && settings.autoRecall && !controller.isCancelled) {
                val source = OpenVikingCancellationSource()
                val handle = cancellation.register(source::cancel)
                try {
                    // Offline capture can allocate a local session before its CREATE is sent.
                    // Recall must not reference a session the server has never acknowledged.
                    val remote = db.openVikingDao().scopedSession(connection, openVikingHash(scope), epoch)
                        ?.takeIf { it.remoteCreated }?.remoteSessionId
                    OpenVikingRecallService().recall(settings, OpenVikingTextSafety.clean(query, credential.apiKey), remote, source,
                        OpenVikingContextSearch { request -> OpenVikingHttpClient(settings.baseUrl, credential.apiKey,
                            settings.actorPeer, settings.foregroundDeadlineMs.toLong(), source).contextSearch(request) })
                } finally { handle.close() }
            } else ""
            val clean = OpenVikingTextSafety.clean(listOf(profile, recall).filter(String::isNotBlank).joinToString("\n\n"), credential?.apiKey.orEmpty())
            val rendered = OpenVikingContextProjection.render(clean)
            turnBlocks[id] = rendered
            return cache.freeze(connection, scope, id, epoch, rendered)
        } finally { lock.unlock() }
    }

    fun compacted(compactionId: String) {
        try { runBlocking { cache.advance(connection, scope, compactionId) }; resolved.clear() }
        catch (failure: Exception) { warn("epoch", failure) }
        OpenVikingSyncScheduler.request(context, force = true)
    }

    private suspend fun stillEnabled(): Boolean {
        if (closed || controller.isCancelled || !AgentMemoryRepository.isEnabled()) return false
        val current = OpenVikingSettingsRepository.settings()
        return current.enabled && current.connectionId() == connection
    }

    override fun close() {
        closed = true; cancellation.cancel(); profileCancellation.cancel(); binding.close(); profileBinding.close()
        syncRegistration.close(); profileRegistration.close()
    }

    companion object {
        private val prewarming = ConcurrentHashMap.newKeySet<String>()
        private val turnLocks = Array(64) { Mutex() }
        private val profileWorker = java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.ArrayBlockingQueue<Runnable>(8), { r -> Thread(r, "eta-ov-profile").apply { isDaemon = true } })
        fun create(context: Context, scope: String, controller: AgentRunController, roleplay: Boolean, memoryEnabled: Boolean,
            skipRecallMessageId: String? = null,
        ): OpenVikingRuntimeContext? {
            if (roleplay || !memoryEnabled) return null
            return try { runBlocking {
                val settings = OpenVikingSettingsRepository.settings()
                if (!settings.enabled) return@runBlocking null
                settings.validate()
                if (OpenVikingSecretStore(context).credential(settings.secretRevision) !is OpenVikingCredentialState.Available) return@runBlocking null
                OpenVikingRuntimeContext(context.applicationContext, settings, scope, controller, skipRecallMessageId)
                    .also { if (skipRecallMessageId == null) it.startPrewarm() }
            } } catch (failure: Exception) { warn("setup", failure); null }
        }
        private fun warn(operation: String, failure: Exception) = AndroidAgentLogger.warnThrottled("openviking_$operation") {
            "OpenViking $operation unavailable: type=${failure.safeLogType()}"
        }
        internal fun boundTokens(text: String, budget: Int): String {
            var used = 0.0
            return text.takeWhile { ch -> used += if (ch.code >= 0x3000) 1.5 else 0.35; used <= budget }
        }
    }
}
