package io.github.mangi.eta.agent.openviking

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.OpenVikingSettings
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.OpenVikingCredentialState
import io.github.mangi.eta.data.repository.OpenVikingSecretStore
import io.github.mangi.eta.data.repository.OpenVikingSettingsRepository
import kotlinx.coroutines.runBlocking

/** Top-level real-user input only. Any tool exposure taints the entire assistant run, including
 * past tool chains: an omitted/redacted tool result is not evidence that its derivative is safe. */
internal class OpenVikingRunCapture private constructor(
    private val context: Context, private val settings: OpenVikingSettings,
    private val scope: String, private val run: String, private val credential: String,
    private val repository: OpenVikingCaptureRepository,
) {
    private val connection = checkNotNull(settings.connectionId())
    private val inputs = mutableListOf<OpenVikingCaptureRepository.Input>()
    private val provenance = OpenVikingCaptureProvenance()

    private fun input(id: String, text: String) = guarded {
        if (inputs.size >= 32) return@guarded
        val clean = OpenVikingCaptureSanitizer.clean(text, credential) ?: return@guarded
        if (inputs.none { it.messageId == id }) inputs += OpenVikingCaptureRepository.Input(id, clean)
        runBlocking { repository.stage(connection, scope, run, inputs) }
    }

    fun accept(event: AgentEvent) {
        provenance.accept(event)
        // Only AgentRequestContext.userInput records supplements actually consumed by the loop;
        // reception events can precede cancellation and must not create a second source id.
    }

    fun userInput(messageId: String, text: String, source: String) {
        if (source == AgentRuntimeWire.SUPPLEMENT_SOURCE_USER) input(messageId, text)
        else provenance.taint()
    }

    fun finish(normal: Boolean, assistant: String, remoteInjected: Boolean) = guarded {
        runBlocking {
            val current = OpenVikingSettingsRepository.settings()
            if (!normal || provenance.failed || !AgentMemoryRepository.isEnabled() || !current.enabled || !current.autoCapture ||
                current.connectionId() != connection) {
                repository.abort(connection, scope, run)
                return@runBlocking
            }
            val text = if (settings.captureAssistantReplies && current.captureAssistantReplies)
                OpenVikingCaptureSanitizer.clean(assistant, credential, assistant = true, tainted = provenance.tainted || remoteInjected) else null
            val epoch = OpenVikingContextCache(EtaDatabase.get(context), OpenVikingSecretStore(context)).epoch(connection, scope)
            repository.finish(connection, scope, run, epoch, settings.actorPeer, text)
            OpenVikingSyncScheduler.refresh(context)
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (failure: Exception) {
            AndroidAgentLogger.warnThrottled("openviking_capture") { "OpenViking capture unavailable: type=${failure.safeLogType()}" }
        }
    }

    companion object {
        fun create(context: Context, scope: String, run: String, messageId: String, user: String,
            roleplay: Boolean, subagent: Boolean = false, userOrigin: Boolean = true,
            history: List<AgentModelClient.ConversationMessage> = emptyList(), screenOrImages: Boolean = false,
        ): OpenVikingRunCapture? {
            if (roleplay || subagent || !userOrigin) return null
            return try { runBlocking {
                if (!AgentMemoryRepository.isEnabled()) return@runBlocking null
                val s = OpenVikingSettingsRepository.settings()
                if (!s.enabled || !s.autoCapture) return@runBlocking null
                s.validate()
                val secrets = OpenVikingSecretStore(context)
                val key = (secrets.credential(s.secretRevision) as? OpenVikingCredentialState.Available)?.apiKey ?: return@runBlocking null
                OpenVikingRunCapture(context.applicationContext, s, scope, run, key,
                    OpenVikingCaptureRepository(EtaDatabase.get(context), secrets)).also {
                    if (screenOrImages || history.any { message ->
                        message.role == "tool" || message.contextSummary || message.toolCallsJson.isNotBlank() || message.contentJson.contains("image")
                    }) it.provenance.taint()
                    it.input(messageId, user)
                }
            } } catch (failure: Exception) {
                AndroidAgentLogger.warnThrottled("openviking_capture_setup") { "OpenViking capture unavailable: type=${failure.safeLogType()}" }
                null
            }
        }
    }
}
