package io.github.mangi.eta.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface OpenVikingPayloadCipher {
    fun encrypt(plaintext: ByteArray, aad: String): ByteArray
    fun decrypt(ciphertext: ByteArray, aad: String): ByteArray
}

internal sealed interface OpenVikingCredentialState {
    data object Missing : OpenVikingCredentialState
    data class Available(val apiKey: String) : OpenVikingCredentialState {
        override fun toString(): String = "Available([redacted])"
    }
    data object Unavailable : OpenVikingCredentialState
}

internal class OpenVikingSecretsUnavailable : IllegalStateException("OpenViking encryption key unavailable")

/** New keys are created only for encryption. A missing decryption key is never silently replaced. */
internal class OpenVikingSecretStore(
    context: Context,
    private val keyRemover: (String) -> Unit = { alias -> removeKey(alias) },
    private val keyProvider: (String, Boolean) -> SecretKey = { alias, create -> key(alias, create) },
) : OpenVikingPayloadCipher {
    private val prefs = context.applicationContext.getSharedPreferences("eta_openviking_secrets", Context.MODE_PRIVATE)

    fun credential(revision: String): OpenVikingCredentialState {
        if (revision.isBlank()) return OpenVikingCredentialState.Missing
        val encoded = prefs.getString("credential_$revision", null) ?: return OpenVikingCredentialState.Unavailable
        return try {
            val bytes = decryptWith(CREDENTIAL_ALIAS, Base64.getDecoder().decode(encoded), "credential:v1:$revision")
            OpenVikingCredentialState.Available(bytes.toString(Charsets.UTF_8))
        } catch (_: Exception) { OpenVikingCredentialState.Unavailable }
    }

    /** Keep old slots until DataStore publishes the new revision; failed saves leave the old pair intact. */
    fun stageCredential(apiKey: String, oldRevision: String): String {
        require(apiKey.isNotBlank() && apiKey.length <= 8192 && apiKey.all { it.code in 0x21..0x7e })
        val old = credential(oldRevision)
        if (old is OpenVikingCredentialState.Available && old.apiKey == apiKey) return oldRevision
        val revision = UUID.randomUUID().toString()
        val encrypted = encryptWith(CREDENTIAL_ALIAS, apiKey.toByteArray(Charsets.UTF_8), "credential:v1:$revision")
        check(prefs.edit().putString("credential_$revision", Base64.getEncoder().encodeToString(encrypted)).commit())
        return revision
    }

    fun pruneCredentials(keepRevision: String) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("credential_") && it != "credential_$keepRevision" }.forEach { edit.remove(it) }
        check(edit.commit())
    }

    fun clearCredentials() {
        pruneCredentials("")
        guarded { keyRemover(CREDENTIAL_ALIAS) }
    }

    override fun encrypt(plaintext: ByteArray, aad: String): ByteArray = encryptWith(PAYLOAD_ALIAS, plaintext, "payload:v1:$aad")
    override fun decrypt(ciphertext: ByteArray, aad: String): ByteArray = decryptWith(PAYLOAD_ALIAS, ciphertext, "payload:v1:$aad")

    private fun encryptWith(alias: String, plaintext: ByteArray, aad: String): ByteArray = guarded {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(alias, true))
        require(cipher.iv.size == 12)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decryptWith(alias: String, bytes: ByteArray, aad: String): ByteArray = guarded {
        require(bytes.size >= 29 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(alias, false), GCMParameterSpec(128, bytes, 1, 12))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        cipher.doFinal(bytes, 13, bytes.size - 13)
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (_: Exception) { throw OpenVikingSecretsUnavailable() }

    companion object {
        private const val CREDENTIAL_ALIAS = "eta_openviking_credentials_v1"
        private const val PAYLOAD_ALIAS = "eta_openviking_payload_v1"

        @Synchronized
        private fun removeKey(alias: String) {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }

        @Synchronized
        private fun key(alias: String, create: Boolean): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            if (!create) throw OpenVikingSecretsUnavailable()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
    }
}
