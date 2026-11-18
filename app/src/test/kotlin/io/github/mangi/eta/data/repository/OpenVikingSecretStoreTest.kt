package io.github.mangi.eta.data.repository

import android.app.Application
import android.content.Context
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpenVikingSecretStoreTest {
    private lateinit var context: Context
    private lateinit var store: OpenVikingSecretStore
    private val keys = mutableMapOf<String, SecretKey>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("eta_openviking_secrets", Context.MODE_PRIVATE).edit().clear().commit()
        store = OpenVikingSecretStore(context, keyRemover = { alias -> keys.remove(alias); Unit }) { alias, create ->
            keys[alias] ?: if (create) KeyGenerator.getInstance("AES").apply { init(128) }.generateKey().also { keys[alias] = it }
            else throw OpenVikingSecretsUnavailable()
        }
    }

    @Test fun credentialSlotsKeepThePublishedOldKeyUntilPruned() {
        val old = store.stageCredential("fixture-old-key", "")
        assertEquals(old, store.stageCredential("fixture-old-key", old))
        val next = store.stageCredential("fixture-new-key", old)
        assertNotEquals(old, next)
        assertEquals("fixture-old-key", (store.credential(old) as OpenVikingCredentialState.Available).apiKey)
        assertEquals("fixture-new-key", (store.credential(next) as OpenVikingCredentialState.Available).apiKey)
        assertFalse(context.getSharedPreferences("eta_openviking_secrets", Context.MODE_PRIVATE).all.toString().contains("fixture-"))
        store.pruneCredentials(next)
        assertEquals(OpenVikingCredentialState.Unavailable, store.credential(old))
        assertEquals(OpenVikingCredentialState.Missing, store.credential(""))
    }

    @Test fun missingDecryptionKeyDoesNotGenerateAReplacement() {
        val revision = store.stageCredential("fixture-key", "")
        keys.clear()
        assertEquals(OpenVikingCredentialState.Unavailable, store.credential(revision))
        assertTrue(keys.isEmpty())
    }

    @Test fun payloadsBindAadAndDetectCorruption() {
        val encrypted = store.encrypt("private payload".toByteArray(), "connection:outbox:row:v1")
        assertEquals("private payload", store.decrypt(encrypted, "connection:outbox:row:v1").toString(Charsets.UTF_8))
        for ((bytes, aad) in listOf(encrypted to "other:outbox:row:v1",
            encrypted.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } to "connection:outbox:row:v1")) {
            try { store.decrypt(bytes, aad); fail("AEAD should reject modified input") }
            catch (_: OpenVikingSecretsUnavailable) { }
        }
    }

    @Test fun clearingCredentialsRotatesOnlyTheCredentialKey() {
        val old = store.stageCredential("fixture-key", "")
        val encrypted = store.encrypt("queued payload".toByteArray(), "connection:outbox:row:v1")
        store.clearCredentials()
        assertEquals(OpenVikingCredentialState.Unavailable, store.credential(old))
        assertFalse(keys.containsKey("eta_openviking_credentials_v1"))
        assertEquals("queued payload", store.decrypt(encrypted, "connection:outbox:row:v1").toString(Charsets.UTF_8))
        val next = store.stageCredential("fixture-new-key", "")
        assertEquals("fixture-new-key", (store.credential(next) as OpenVikingCredentialState.Available).apiKey)
    }
}
