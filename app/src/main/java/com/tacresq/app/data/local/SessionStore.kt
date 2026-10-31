package com.tacresq.app.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.tacresq.app.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.onEach
import java.io.IOException
import kotlinx.coroutines.delay
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
    private val dataStore: DataStore<Preferences> = context.sessionDataStore,
) {
    private val gson = Gson()
    private val inMemorySession = MutableStateFlow<Session?>(null)
    private val sessionReadRetrySignal = MutableStateFlow(0)
    val sessionReadError = MutableStateFlow(false)
    val sessionReadLoading = MutableStateFlow(persistSessions)
    private val encryptedSessionKey = stringPreferencesKey("encrypted_session_v1")
    private val pendingIncidentEnvelopeKey = stringPreferencesKey("pending_incident_envelope_v1")
    private val pendingIncidentMutex = Mutex()
    // Fake/test API mode must never write pending SOS state into the real app's DataStore.
    private var inMemoryPendingIncident: PendingIncidentSubmission? = null

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val session: Flow<Session?> = if (persistSessions) {
        sessionReadRetrySignal.flatMapLatest {
            dataStore.data
                // Retry transient storage failures, but bound the retry window so startup can
                // expose a recoverable error instead of remaining on an endless loading screen.
                .retryWhen { cause, attempt ->
                    if (shouldRetrySessionStoreRead(cause, attempt)) {
                        delay(sessionStoreReadRetryDelayMillis(attempt))
                        true
                    } else {
                        false
                    }
                }
                .onEach { prefs ->
                    // Remove pre-encryption credentials as soon as this store is first read,
                    // not only after the user signs in successfully again.
                    if (prefs.contains(stringPreferencesKey("token")) ||
                        prefs.contains(stringPreferencesKey("user_json"))
                    ) {
                        dataStore.edit { current ->
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
                .onEach {
                    sessionReadError.value = false
                    sessionReadLoading.value = false
                }
                .catch { cause ->
                    // Cancellation is control flow, not a restore failure. Surface other
                    // storage exceptions too, so an unexpected provider/security error cannot
                    // strand the app on the initial loading state.
                    if (cause is kotlinx.coroutines.CancellationException) throw cause
                    if (cause is Exception) {
                        sessionReadError.value = true
                        sessionReadLoading.value = false
                        emit(null)
                    } else {
                        throw cause
                    }
                }
        }
    } else {
        inMemorySession
    }

    /** Retry restoring the saved session after a recoverable DataStore read failure. */
    fun retrySessionRead() {
        sessionReadError.value = false
        sessionReadLoading.value = true
        sessionReadRetrySignal.value += 1
    }

    suspend fun save(session: Session) {
        if (!persistSessions) {
            inMemorySession.value = session
            return
        }
        val encrypted = encrypt(gson.toJson(session))
        dataStore.edit { prefs ->
            // Remove legacy plaintext token/user preferences if present.
            prefs.remove(stringPreferencesKey("token"))
            prefs.remove(stringPreferencesKey("user_json"))
            prefs[encryptedSessionKey] = encrypted
        }
    }

    data class PendingIncidentSubmission(
        val requestId: String,
        val fingerprint: String,
        val payloadJson: String,
        val ownerUserId: Long? = null,
    )

    class PendingIncidentConflictException(
        message: String = "An earlier SOS submission is still unresolved. Retry it with the original details before creating another SOS."
    ) : IllegalStateException(message)

    private fun requirePendingOwner(saved: PendingIncidentSubmission, ownerUserId: Long) {
        if (saved.ownerUserId != ownerUserId) {
            val message = if (saved.ownerUserId == null) {
                "An older saved SOS has no verified requester owner. Contact dispatch before creating another request; this account cannot safely retry or discard it."
            } else {
                "A saved SOS belongs to a different requester account on this device. Sign in with the original requester account to resolve it; this account cannot send or discard that request."
            }
            throw PendingIncidentConflictException(message)
        }
    }

    /** Persist the exact payload and key together; never overwrite an ambiguous submission. */
    suspend fun getOrCreatePendingIncident(
        fingerprint: String,
        payloadJson: String,
        ownerUserId: Long,
    ): PendingIncidentSubmission = pendingIncidentMutex.withLock {
        require(ownerUserId > 0L) { "A signed-in requester account is required to save an SOS." }
        if (!persistSessions) {
            val saved = inMemoryPendingIncident
            if (saved != null) {
                requirePendingOwner(saved, ownerUserId)
                if (saved.fingerprint != fingerprint) throw PendingIncidentConflictException()
                return@withLock saved
            }
            return@withLock PendingIncidentSubmission(UUID.randomUUID().toString(), fingerprint, payloadJson, ownerUserId)
                .also { inMemoryPendingIncident = it }
        }
        var submission: PendingIncidentSubmission? = null
        dataStore.edit { prefs ->
            val hasSavedEnvelope = prefs.contains(pendingIncidentEnvelopeKey)
            val saved = prefs[pendingIncidentEnvelopeKey]?.let { encoded ->
                runCatching { gson.fromJson(decrypt(encoded), PendingIncidentSubmission::class.java) }
                    .getOrElse { throw PendingIncidentConflictException() }
            }
            if (hasSavedEnvelope && saved == null) throw PendingIncidentConflictException()
            if (saved != null) {
                requirePendingOwner(saved, ownerUserId)
                if (saved.fingerprint != fingerprint) throw PendingIncidentConflictException()
                submission = saved
            } else {
                val legacyId = prefs[stringPreferencesKey("pending_incident_request_id")]
                val legacyFingerprint = prefs[stringPreferencesKey("pending_incident_fingerprint")]
                if (!legacyId.isNullOrBlank() || !legacyFingerprint.isNullOrBlank()) {
                    throw PendingIncidentConflictException(
                        "An older saved SOS has no verified requester owner. Contact dispatch before creating another request."
                    )
                }
                val created = PendingIncidentSubmission(UUID.randomUUID().toString(), fingerprint, payloadJson, ownerUserId)
                prefs[pendingIncidentEnvelopeKey] = encrypt(gson.toJson(created))
                prefs.remove(stringPreferencesKey("pending_incident_request_id"))
                prefs.remove(stringPreferencesKey("pending_incident_fingerprint"))
                submission = created
            }
        }
        checkNotNull(submission)
    }

    /** Returns the saved submission so the UI can retry it without rebuilding from current GPS/form state. */
    suspend fun getPendingIncidentSubmission(ownerUserId: Long): PendingIncidentSubmission? = pendingIncidentMutex.withLock {
        require(ownerUserId > 0L) { "A signed-in requester account is required to restore an SOS." }
        if (!persistSessions) {
            val saved = inMemoryPendingIncident ?: return@withLock null
            requirePendingOwner(saved, ownerUserId)
            return@withLock saved
        }
        val prefs = dataStore.data.first()
        val encoded = prefs[pendingIncidentEnvelopeKey] ?: return@withLock null
        val saved = runCatching { gson.fromJson(decrypt(encoded), PendingIncidentSubmission::class.java) }
            .getOrElse { throw PendingIncidentConflictException() }
            ?: throw PendingIncidentConflictException()
        requirePendingOwner(saved, ownerUserId)
        saved
    }

    suspend fun clearPendingIncidentRequestId(requestId: String, ownerUserId: Long) {
        pendingIncidentMutex.withLock {
            if (!persistSessions) {
                val saved = inMemoryPendingIncident
                if (saved?.requestId == requestId) {
                    requirePendingOwner(saved, ownerUserId)
                    inMemoryPendingIncident = null
                }
                return@withLock
            }
            dataStore.edit { prefs ->
                val saved = prefs[pendingIncidentEnvelopeKey]?.let { encoded -> runCatching { gson.fromJson(decrypt(encoded), PendingIncidentSubmission::class.java) }.getOrNull() }
                if (saved?.requestId == requestId) {
                    requirePendingOwner(saved, ownerUserId)
                    prefs.remove(pendingIncidentEnvelopeKey)
                }
            }
        }
    }

    suspend fun clear() {
        if (!persistSessions) {
            inMemorySession.value = null
            pendingIncidentMutex.withLock { inMemoryPendingIncident = null }
            return
        }
        dataStore.edit { prefs ->
            // Preserve unresolved SOS submissions across logout so a retry cannot get a new key.
            prefs.remove(encryptedSessionKey)
            prefs.remove(stringPreferencesKey("token"))
            prefs.remove(stringPreferencesKey("user_json"))
        }
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

/** Capped exponential backoff for recoverable DataStore read failures. */
internal const val SESSION_STORE_MAX_READ_RETRIES = 5L

internal fun shouldRetrySessionStoreRead(cause: Throwable): Boolean =
    cause is IOException && cause !is kotlinx.coroutines.CancellationException

internal fun shouldRetrySessionStoreRead(cause: Throwable, attempt: Long): Boolean =
    shouldRetrySessionStoreRead(cause) && attempt < SESSION_STORE_MAX_READ_RETRIES

internal fun sessionStoreReadRetryDelayMillis(attempt: Long): Long {
    val exponent = attempt.coerceIn(0L, 5L).toInt()
    return (1_000L shl exponent).coerceAtMost(30_000L)
}
