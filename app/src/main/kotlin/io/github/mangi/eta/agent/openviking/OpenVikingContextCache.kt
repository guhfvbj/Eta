package io.github.mangi.eta.agent.openviking

import androidx.room.withTransaction
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.OpenVikingCacheEntity
import io.github.mangi.eta.data.db.OpenVikingInjectionEntity
import io.github.mangi.eta.data.model.openVikingHash
import io.github.mangi.eta.data.repository.OpenVikingPayloadCipher

internal class OpenVikingContextCache(
    private val db: EtaDatabase,
    private val cipher: OpenVikingPayloadCipher,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.openVikingDao()
    suspend fun epoch(connection: String, scope: String): Int =
        dao.cache(connection, "epoch", openVikingHash(scope))?.let {
            cipher.decrypt(it.payloadCipher, aad(connection, "epoch", it.cacheKey)).toString(Charsets.UTF_8).substringBefore('\n').toInt()
        } ?: 0

    /** Idempotent for duplicate completed events. Ordinary checkpoints never call this. */
    suspend fun advance(connection: String, scope: String, compactionId: String): Int = db.withTransaction {
        val key = openVikingHash(scope)
        val old = dao.cache(connection, "epoch", key)?.let {
            cipher.decrypt(it.payloadCipher, aad(connection, "epoch", key)).toString(Charsets.UTF_8)
        }.orEmpty()
        val current = old.substringBefore('\n').toIntOrNull() ?: 0
        if (old.substringAfter('\n', "") == compactionId) return@withTransaction current
        val next = Math.addExact(current, 1)
        dao.putCache(OpenVikingCacheEntity(connection, "epoch", key,
            cipher.encrypt("$next\n$compactionId".toByteArray(Charsets.UTF_8), aad(connection, "epoch", key)), Long.MAX_VALUE))
        next
    }

    suspend fun frozen(connection: String, scope: String, message: String, epoch: Int): String? =
        dao.injection(connection, openVikingHash(scope), message, epoch)?.let {
            it.payloadCipher?.let { bytes -> cipher.decrypt(bytes, injectionAad(connection, scope, message, epoch)).toString(Charsets.UTF_8) } ?: ""
        }

    suspend fun freeze(connection: String, scope: String, message: String, epoch: Int, rendered: String): String = db.withTransaction {
        frozen(connection, scope, message, epoch)?.let { return@withTransaction it }
        dao.putInjection(OpenVikingInjectionEntity(connection, openVikingHash(scope), message, epoch,
            if (rendered.isBlank()) "EMPTY" else "INJECTED",
            rendered.takeIf(String::isNotBlank)?.let { cipher.encrypt(it.toByteArray(Charsets.UTF_8), injectionAad(connection, scope, message, epoch)) },
            tokenCount = rendered.sumOf { if (it.code >= 0x3000) 1.5 else 0.35 }.toInt(), createdAt = now()))
        rendered
    }

    suspend fun profile(connection: String): String? = dao.cache(connection, "profile", "v1")?.takeIf { it.expiresAt > now() }?.let {
        cipher.decrypt(it.payloadCipher, aad(connection, "profile", "v1")).toString(Charsets.UTF_8)
    }
    suspend fun putProfile(connection: String, text: String) = dao.putCache(OpenVikingCacheEntity(connection, "profile", "v1",
        cipher.encrypt(text.toByteArray(Charsets.UTF_8), aad(connection, "profile", "v1")), now() + 10 * 60_000L))

    private fun injectionAad(c: String, s: String, m: String, e: Int) = "$c:injections:${openVikingHash(s)}:$m:$e:v1"
    private fun aad(c: String, kind: String, key: String) = "$c:cache:$kind:$key:v1"
}
