package io.github.mangi.eta.agent.openviking

import android.content.Context
import androidx.room.Room
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.OpenVikingSettings
import io.github.mangi.eta.data.repository.OpenVikingPayloadCipher
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

internal class TestPayloadCipher : OpenVikingPayloadCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
    override fun encrypt(plaintext: ByteArray, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad.toByteArray())
        return cipher.iv + cipher.doFinal(plaintext)
    }
    override fun decrypt(ciphertext: ByteArray, aad: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext, 0, 12))
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }
}

internal class OpenVikingTestFixture(context: Context, quota: Long = 10L * 1024 * 1024) : AutoCloseable {
    val clock = AtomicLong(1_000_000)
    val database = Room.inMemoryDatabaseBuilder(context, EtaDatabase::class.java).build()
    val cipher = TestPayloadCipher()
    val outbox = OpenVikingOutboxRepository(database, cipher, clock::get, quota)
    val settings = OpenVikingSettings(enabled = true, baseUrl = "https://example.invalid", secretRevision = "fixture-revision")
    val connection = checkNotNull(settings.connectionId())
    val coordinator = OpenVikingSyncCoordinator(outbox, clock::get)
    suspend fun add(source: String = "m1", content: String = "A durable user preference", tokens: Int = 20,
        priority: OpenVikingPriority = OpenVikingPriority.AUTO, turn: String = source): Boolean =
        outbox.enqueue(connection, "conversation", source, 1, AddMessageRequest("user", content, turnId = turn), tokens, priority)
    override fun close() { database.close() }
}

internal class FakeOpenVikingApi : OpenVikingSyncApi {
    val creates = AtomicInteger()
    val batches = AtomicInteger()
    val commits = AtomicInteger()
    val polls = AtomicInteger()
    val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()
    val uploaded = java.util.concurrent.CopyOnWriteArrayList<AddMessageRequest>()
    var batchFailure: OpenVikingApiException? = null
    var taskStatus = "completed"
    var delayMs = 0L
    private fun <T> call(block: () -> T): T {
        val count = inFlight.incrementAndGet()
        maxInFlight.updateAndGet { maxOf(it, count) }
        try { if (delayMs > 0) Thread.sleep(delayMs); return block() } finally { inFlight.decrementAndGet() }
    }
    override fun createSession(request: CreateSessionRequest) = call { creates.incrementAndGet(); Unit }
    override fun addMessages(sessionId: String, request: BatchAddMessageRequest) = call {
        batches.incrementAndGet()
        batchFailure?.let { throw it }
        check(request.messages.size <= 20)
        uploaded.addAll(request.messages)
        Unit
    }
    override fun commit(sessionId: String, request: CommitRequest): CommitResponse = call {
        CommitResponse("task-${commits.incrementAndGet()}")
    }
    override fun task(taskId: String): TaskStatusResponse = call { polls.incrementAndGet(); TaskStatusResponse(taskStatus, taskId) }
}
