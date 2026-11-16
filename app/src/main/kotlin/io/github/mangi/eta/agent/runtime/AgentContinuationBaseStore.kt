package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import java.security.MessageDigest
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** 成功对话的续跑基座；完全不保存 config，唤醒时从 RuntimeConfigRepository 重新解析。 */
internal class AgentContinuationBaseStore(private val rootDir: File) {
    data class Base(
        val conversationId: String,
        val runId: String,
        val request: AgentRuntimeWire.RunRequest,
        val snapshot: AgentContextSnapshot?,
        val transcript: List<AgentModelClient.ConversationMessage>,
        val savedAt: Long,
    )

    fun save(base: Base) {
        io {
            runCatching {
                val bytes = encode(base).toString().toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) {
                    warn("续跑基座超过 512 KiB，放弃保存")
                    // 不能让更旧基座在新结果超限后继续被用于自动续跑。
                    file(base.conversationId).delete()
                    return@runCatching
                }
                check(rootDir.isDirectory || rootDir.mkdirs())
                val target = file(base.conversationId)
                val temp = File(rootDir, "${target.name}.tmp")
                try {
                    temp.outputStream().use { it.write(bytes); it.fd.sync() }
                    try {
                        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    } catch (_: AtomicMoveNotSupportedException) {
                        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                } finally { temp.delete() }
                val records = rootDir.listFiles().orEmpty().filter { it.extension == "json" }
                    .mapNotNull { record -> read(record)?.let { record to it.savedAt } }
                records.sortedBy { it.second }.take((records.size - MAX_BASES).coerceAtLeast(0))
                    .forEach { it.first.delete() }
            }.onFailure { warn("续跑基座保存失败：${it.javaClass.simpleName}") }
        }
    }

    fun load(conversationId: String): Base? = io {
        val target = file(conversationId)
        read(target)?.takeIf { base ->
            val valid = base.conversationId == conversationId && System.currentTimeMillis() - base.savedAt <= TTL_MS
            if (!valid) target.delete()
            valid
        }
    }

    fun remove(conversationId: String) {
        io { runCatching { file(conversationId).delete() } }
    }

    private fun read(target: File): Base? {
        if (!target.exists()) return null
        return runCatching {
            require(target.length() <= MAX_BYTES)
            decode(JSONObject(target.readText(Charsets.UTF_8)))
        }.getOrElse { target.delete(); null }
    }

    private fun encode(base: Base): JSONObject {
        val request = base.request
        val safeSnapshot = base.snapshot?.let {
            it.copy(messages = AgentConversationCodec.decodeTranscript(AgentConversationCodec.encodeTranscriptForStorage(it.messages)))
        }
        return JSONObject().put("conversationId", base.conversationId).put("runId", base.runId)
            .put("savedAt", base.savedAt)
            .put("snapshot", safeSnapshot?.encode() ?: JSONObject.NULL)
            .put("transcript", JSONArray(AgentConversationCodec.encodeTranscriptForStorage(base.transcript)))
            .put("request", JSONObject().put("runId", request.runId).put("prompt", request.prompt)
                .put("operation", request.operation).put("modelSessionId", request.effectiveModelSessionId)
                .put("history", JSONArray(AgentConversationCodec.encodeTranscriptForStorage(request.history)))
                .put("images", JSONArray().also { array -> request.images.forEach { image ->
                    array.put(JSONObject().put("reference", image.reference).put("mimeType", image.mimeType)
                        .put("bytes", image.bytes).put("width", image.width ?: JSONObject.NULL)
                        .put("height", image.height ?: JSONObject.NULL).put("source", image.source)
                        .put("preserveOriginal", image.preserveOriginal))
                } })
                .put("handoff", request.handoff?.let { handoff ->
                    JSONObject().put("id", handoff.id).put("source", handoff.source).put("payload", handoff.payload)
                        .put("dismissEntrySurfaceOnForegroundOperation", handoff.dismissEntrySurfaceOnForegroundOperation)
                } ?: JSONObject.NULL))
    }

    private fun decode(json: JSONObject): Base {
        val stored = json.getJSONObject("request")
        val handoff = stored.optJSONObject("handoff")?.let {
            AgentRuntimeWire.EntryHandoff(it.getString("id"), it.getString("source"), it.getString("payload"),
                it.optBoolean("dismissEntrySurfaceOnForegroundOperation", false))
        }
        val images = stored.optJSONArray("images") ?: JSONArray()
        return Base(
            conversationId = json.getString("conversationId"), runId = json.getString("runId"),
            savedAt = json.getLong("savedAt"),
            request = AgentRuntimeWire.RunRequest(
                runId = stored.getString("runId"), prompt = stored.getString("prompt"),
                config = AgentModelClient.ModelConfig(baseUrl = "", apiKey = "", model = "", systemPrompt = ""),
                operation = stored.optString("operation", AgentRuntimeWire.OP_CHAT),
                modelSessionId = stored.optString("modelSessionId", ""), handoff = handoff,
                history = AgentConversationCodec.decodeTranscript(stored.optJSONArray("history")?.toString()),
                images = (0 until images.length()).map { index -> images.getJSONObject(index).let {
                    AgentModelClient.ModelImage(it.getString("reference"), it.getString("mimeType"), it.optInt("bytes"),
                        it.optInt("width").takeIf { value -> value > 0 }, it.optInt("height").takeIf { value -> value > 0 },
                        it.optString("source", "unknown"), it.optBoolean("preserveOriginal", false))
                } },
            ),
            snapshot = AgentContextSnapshot.decode(json.optString("snapshot", "").takeUnless { it == "null" }),
            transcript = AgentConversationCodec.decodeTranscript(json.optJSONArray("transcript")?.toString()),
        )
    }

    /** 多个默认 store 实例共用锁，读、淘汰与原子替换不交叉。 */
    private fun <T> io(block: () -> T): T = runBlocking(Dispatchers.IO) { synchronized(LOCK) { block() } }
    private fun file(id: String): File = File(rootDir,
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".json")

    companion object {
        private val LOCK = Any()
        private const val MAX_BYTES = 512 * 1024
        private const val MAX_BASES = 8
        private const val TTL_MS = 24 * 60 * 60 * 1000L
        fun default(context: Context) = AgentContinuationBaseStore(File(context.filesDir, "agent/continuation-bases"))
        private fun warn(message: String) { runCatching { AndroidAgentLogger.warn(message) } }
    }
}
