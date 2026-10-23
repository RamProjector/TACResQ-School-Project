package com.tacresq.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.tacresq.app.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.sessionDataStore by preferencesDataStore(name = "tacresq_session")

/**
 * Persists the session encrypted with an AES-GCM key held by Android Keystore.
 *
 * Existing plaintext sessions cannot be migrated safely without reading them. They are treated
 * as signed out after this update; users must sign in again. If Keystore decryption fails, the
 * session is not restored rather than falling back to plaintext.
 */
class SessionStore(
    private val context: Context,
    private val persistSessions: Boolean = true,
) {
    private val gson = Gson()
    private val inMemorySession = MutableStateFlow<Session?>(null)
    private val encryptedSessionKey = stringPreferencesKey("encrypted_session_v1")
    private val pendingIncidentRequestIdKey = stringPreferencesKey("pending_incident_request_id")
    private val pendingIncidentFingerprintKey = stringPreferencesKey("pending_incident_fingerprint")
    private val pendingIncidentMutex = Mutex()

    val session: Flow<Session?> = if (persistSessions) {
        context.sessionDataStore.data
            .catch { e ->
                if (e is IOException) emit(emptyPreferences()) else throw e
            }
            .onEach { prefs ->
                // Remove pre-encryption credentials as soon as this store is first read,
                // not only after the user signs in successfully again.
                if (prefs.contains(stringPreferencesKey("token")) ||
                    prefs.contains(stringPreferencesKey("user_json"))
                ) {
                    context.sessionDataStore.edit { current ->
                        current.remove(stringPreferencesKey("token"))
                        current.remove(stringPreferencesKey("user_json"))
                    }
                }
            }
            .map { prefs ->
                prefs[encryptedSessionKey]
                    ?.let { encrypted -> runCatching { decrypt(encrypted) }.getOrNull() }
                    ?.let { json -> runCatching { gson.fromJson(json, Session::class.java) }.getOrNull() }
            }
    } else {
        inMemorySession
    }

    suspend fun save(session: Session) {
        if (!persistSessions) {
            inMemorySession.value = session
            return
        }
        val encrypted = encrypt(gson.toJson(session))
        context.sessionDataStore.edit { prefs ->
            // Remove legacy plaintext token/user preferences if present.
            prefs.remove(stringPreferencesKey("token"))
            prefs.remove(stringPreferencesKey("user_json"))
            prefs[encryptedSessionKey] = encrypted
        }
    }

    /** Reuse a key only for the exact same SOS payload after an ambiguous network failure. */
    suspend fun pendingIncidentRequestId(fingerprint: String): String = pendingIncidentMutex.withLock {
        var requestId = ""
        context.sessionDataStore.edit { prefs ->
            val savedFingerprint = prefs[pendingIncidentFingerprintKey]
            val savedId = prefs[pendingIncidentRequestIdKey]
            if (savedId.isNullOrBlank() || savedFingerprint != fingerprint) {
                requestId = UUID.randomUUID().toString()
                prefs[pendingIncidentRequestIdKey] = requestId
                prefs[pendingIncidentFingerprintKey] = fingerprint
            } else requestId = savedId
        }
        requestId
    }

    suspend fun clearPendingIncidentRequestId(requestId: String) {
        pendingIncidentMutex.withLock {
            context.sessionDataStore.edit { prefs ->
                if (prefs[pendingIncidentRequestIdKey] == requestId) {
                    prefs.remove(pendingIncidentRequestIdKey)
                    prefs.remove(pendingIncidentFingerprintKey)
                }
            }
        }
    }

    suspend fun clear() {
        if (!persistSessions) {
            inMemorySession.value = null
            return
        }
        context.sessionDataStore.edit { prefs -> prefs.clear() }
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + cipherText
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH_BYTES) { "Invalid encrypted session" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val cipherText = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "tacresq_session_aes_gcm_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
