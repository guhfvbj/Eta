package io.github.mangi.eta.agent.openviking

import androidx.room.withTransaction
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.OpenVikingCacheEntity
import io.github.mangi.eta.data.model.openVikingHash
import io.github.mangi.eta.data.repository.OpenVikingPayloadCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable, sanitized input ledger, independent of UI messages and transcript projection.
 * DRAFT records are never uploaded. A normal-completion transaction consumes them into outbox.
 * Receipts are independent of epoch so a retry after compaction cannot upload a source twice. */
internal class OpenVikingCaptureRepository(private val db: EtaDatabase, private val cipher: OpenVikingPayloadCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Serializable internal data class Input(val messageId: String, val text: String)
    private val dao = db.openVikingDao()
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(scope: String, run: String) = openVikingHash("$scope:$run")
    private fun aad(connection: String, kind: String, key: String) = "$connection:capture:$kind:$key:v1"

    suspend fun stage(connection: String, scope: String, run: String, inputs: List<Input>) {
        require(inputs.size <= 32)
        require(inputs.all { it.messageId.isNotBlank() && OpenVikingCaptureSanitizer.clean(it.text) == it.text })
        val key = key(scope, run)
        val bytes = json.encodeToString(inputs).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 384 * 1024)
        db.withTransaction {
            dao.deleteExpiredCaptureDrafts(connection, now())
            if (dao.cacheCount(connection, "capture_draft") >= 64 && dao.cache(connection, "capture_draft", key) == null) return@withTransaction
            dao.putCache(OpenVikingCacheEntity(connection, "capture_draft", key,
                cipher.encrypt(bytes, aad(connection, "draft", key)), now() + 24 * 60 * 60_000L))
        }
    }

    suspend fun finish(connection: String, scope: String, run: String, epoch: Int, actor: String, assistant: String?): Int = db.withTransaction {
        val key = key(scope, run)
        val draft = dao.cache(connection, "capture_draft", key)?.takeIf { it.expiresAt > now() } ?: return@withTransaction 0
        val inputs = json.decodeFromString<List<Input>>(cipher.decrypt(draft.payloadCipher, aad(connection, "draft", key)).toString(Charsets.UTF_8))
        val outbox = OpenVikingOutboxRepository(db, cipher, now)
        var count = 0
        val messages = inputs.map { Triple(it.messageId, "user", it.text) }.toMutableList()
        if (assistant != null && inputs.isNotEmpty()) {
            require(OpenVikingCaptureSanitizer.clean(assistant, assistant = true) == assistant)
            messages += Triple("assistant:${inputs.last().messageId}", "assistant", assistant)
        }
        for ((source, role, text) in messages) {
            val receipt = openVikingHash("$scope:$source:v${OpenVikingCaptureSanitizer.VERSION}")
            if (dao.cache(connection, "capture_receipt", receipt) != null) continue
            val turn = openVikingHash("$scope:${if (role == "assistant") inputs.last().messageId else source}")
            if (outbox.enqueue(connection, scope, source, OpenVikingCaptureSanitizer.VERSION,
                    AddMessageRequest(role, text, peerId = actor, turnId = turn),
                    text.sumOf { if (it.code >= 0x3000) 1.5 else 0.35 }.toInt(), epoch = epoch)) count++
            dao.putCache(OpenVikingCacheEntity(connection, "capture_receipt", receipt,
                cipher.encrypt(byteArrayOf(1), aad(connection, "receipt", receipt)), Long.MAX_VALUE))
        }
        dao.deleteCache(connection, "capture_draft", key)
        count
    }

    suspend fun abort(connection: String, scope: String, run: String) = dao.deleteCache(connection, "capture_draft", key(scope, run))
}
