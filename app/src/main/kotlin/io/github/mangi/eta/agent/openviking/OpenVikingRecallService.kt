package io.github.mangi.eta.agent.openviking

import io.github.mangi.eta.data.model.OpenVikingSettings
import io.github.mangi.eta.data.model.OpenVikingRewriteMode
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonPrimitive

internal fun interface OpenVikingContextSearch {
    fun search(request: ContextSearchRequest): ContextSearchResponse
}

/** Bounded even when a transport misbehaves; cancellation closes the active OkHttp call. No retry. */
internal class OpenVikingRecallService(
    private val onFailure: (Exception) -> Unit = { failure ->
        AndroidAgentLogger.warnThrottled("openviking_recall_failed") { "OpenViking recall unavailable: type=${failure.safeLogType()}" }
    },
) {
    fun recall(settings: OpenVikingSettings, query: String, sessionId: String?,
        cancellation: OpenVikingCancellationSource,
        search: OpenVikingContextSearch,
    ): String {
        if (!settings.enabled || !settings.autoRecall || query.isBlank()) return ""
        val future = try { workers.submit<String> { search.search(request(settings, query, sessionId)).injectableText() } }
        catch (failure: Exception) {
            onFailure(failure)
            return ""
        }
        val binding = cancellation.register { future.cancel(true) }
        return try { future.get(settings.foregroundDeadlineMs.toLong(), TimeUnit.MILLISECONDS) }
        catch (failure: Exception) {
            cancellation.cancel()
            future.cancel(true)
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            onFailure(failure)
            ""
        } finally { binding.close() }
    }

    companion object {
        // A hung transport cannot create unbounded foreground workers/queued requests.
        private val workers = java.util.concurrent.ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            java.util.concurrent.SynchronousQueue(), { r -> Thread(r, "eta-ov-recall").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
        fun request(s: OpenVikingSettings, query: String, sessionId: String? = null) = ContextSearchRequest(
            query = query.take(16_000), sessionId = sessionId,
            queryExpansion = s.queryExpansion.name.lowercase(), maxTokens = s.recallMaxTokens,
            purpose = s.purpose.name.lowercase(), scoreThreshold = s.scoreThreshold, dedupTurns = s.dedupTurns,
            peerScope = s.peerScope.name.lowercase(), rewrite = when (s.rewrite) {
                OpenVikingRewriteMode.AUTO -> JsonPrimitive("auto")
                OpenVikingRewriteMode.ON -> JsonPrimitive(true)
                OpenVikingRewriteMode.OFF -> JsonPrimitive(false)
            },
        )
    }
}
