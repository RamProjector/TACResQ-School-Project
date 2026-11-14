package com.tacresq.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.tacresq.app.data.repo.TacRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Keeps the current FCM registration token associated with the authenticated account.
 * The token is never logged and Firebase credentials are never stored in the APK.
 *
 * Token refresh, account sync, and sign-out cleanup are serialized. The generation check
 * prevents a refresh callback queued before sign-out from registering the token afterwards.
 */
class PushTokenCoordinator(
    context: Context,
    private val appScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val operations = Mutex()

    @Volatile
    private var registrationGeneration = 0L

    @Volatile
    private var registrationEnabled = false

    suspend fun sync(repository: TacRepository) {
        val generation = registrationGeneration
        syncInternal(repository, expectedGeneration = generation)
    }

    private suspend fun syncInternal(
        repository: TacRepository,
        expectedGeneration: Long,
        tokenOverride: String? = null,
    ) {
        operations.withLock {
            if (expectedGeneration != registrationGeneration) {
                return@withLock
            }
            registrationEnabled = true
            if (!firebaseConfigured()) return@withLock

            if (!tokenOverride.isNullOrBlank()) {
                preferences.edit().putString(KEY_PENDING_TOKEN, tokenOverride).apply()
            }
            val token = tokenOverride?.takeIf { it.isNotBlank() } ?: currentToken() ?: return@withLock
            val previouslyRegistered = preferences.getString(KEY_REGISTERED_TOKEN, null)

            // Always reconcile ownership on authenticated session restore/login. The
            // server RPC is idempotent and transfers this installation token on account
            // switch, including after an earlier offline sign-out cleanup.
            repository.registerFcmToken(token)
                .onSuccess {
                    var previousTokenRemoved = true
                    if (!previouslyRegistered.isNullOrBlank() && previouslyRegistered != token) {
                        previousTokenRemoved = try {
                            repository.unregisterFcmToken(previouslyRegistered).isSuccess
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                    }

                    // Keep the old registered value until its server-side cleanup is
                    // confirmed. The pending value holds the new token, so a later
                    // authenticated sync can retry removal without losing either value.
                    val editor = preferences.edit().putString(KEY_PENDING_TOKEN, token)
                    if (previousTokenRemoved) {
                        editor.putString(KEY_REGISTERED_TOKEN, token)
                    }
                    editor.apply()
                }
        }
    }

    fun onTokenRefreshed(token: String, repository: TacRepository) {
        if (token.length !in 20..4096) return
        // Capture the generation before checking the flag. If sign-out starts
        // between these reads, either the flag is false or the generation check
        // inside syncInternal rejects this callback.
        val generation = registrationGeneration
        if (!registrationEnabled) {
            preferences.edit().putString(KEY_PENDING_TOKEN, token).apply()
            return
        }
        appScope.launch {
            syncInternal(
                repository,
                expectedGeneration = generation,
                tokenOverride = token,
            )
        }
    }

    suspend fun unregister(repository: TacRepository) {
        // Invalidate callbacks before waiting for any in-flight sync to finish.
        registrationEnabled = false
        registrationGeneration += 1

        operations.withLock {
            val tokens = linkedSetOf(
                preferences.getString(KEY_REGISTERED_TOKEN, null),
                preferences.getString(KEY_PENDING_TOKEN, null),
            ).filterNotNull().filter { it.isNotBlank() }

            var cleanupFailed = false
            for (token in tokens) {
                try {
                    // TacRepository returns Result<Unit>; a failed HTTP/RPC call is
                    // represented as Result.failure and does not throw.
                    if (repository.unregisterFcmToken(token).isFailure) {
                        cleanupFailed = true
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    cleanupFailed = true
                }
            }

            if (!cleanupFailed) {
                preferences.edit()
                    .remove(KEY_REGISTERED_TOKEN)
                    .remove(KEY_PENDING_TOKEN)
                    .apply()
            }
            // If the network is unavailable, keep the local token so the next
            // authenticated sync can reconcile its server-side account ownership.
        }
    }

    private suspend fun currentToken(): String? {
        val pending = preferences.getString(KEY_PENDING_TOKEN, null)
        if (!pending.isNullOrBlank()) return pending
        val token = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return token
            ?.takeIf { it.length in 20..4096 }
            ?.also { preferences.edit().putString(KEY_PENDING_TOKEN, it).apply() }
    }

    private fun firebaseConfigured(): Boolean =
        FirebaseApp.getApps(appContext).isNotEmpty()

    companion object {
        private const val PREFERENCES = "tacresq_push"
        private const val KEY_PENDING_TOKEN = "pending_token"
        private const val KEY_REGISTERED_TOKEN = "registered_token"

        fun notificationsAllowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
    }
}

object PushNotificationChannels {
    const val ALERTS_CHANNEL = "tacresq_alerts"

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                ALERTS_CHANNEL,
                "Incident alerts",
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Prompts to review new TACResQ incident updates"
            },
        )
    }
}
