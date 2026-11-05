package com.tacresq.app.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.tacresq.app.data.repo.TacRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Keeps the current FCM registration token associated with the authenticated account.
 * The token is never logged and Firebase credentials are never stored in the APK.
 */
class PushTokenCoordinator(
    context: Context,
    private val appScope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    suspend fun sync(repository: TacRepository) {
        if (!firebaseConfigured()) return
        val token = currentToken() ?: return
        val previouslyRegistered = preferences.getString(KEY_REGISTERED_TOKEN, null)
        if (previouslyRegistered == token) return

        // Register the new token before removing the old one so a token rotation does not
        // create a delivery gap. The server remains the source of truth for authorization.
        repository.registerFcmToken(token)
            .onSuccess {
                if (!previouslyRegistered.isNullOrBlank() && previouslyRegistered != token) {
                    repository.unregisterFcmToken(previouslyRegistered)
                }
                preferences.edit().putString(KEY_REGISTERED_TOKEN, token).apply()
            }
    }

    fun onTokenRefreshed(token: String, repository: TacRepository) {
        if (token.length !in 20..4096) return
        preferences.edit().putString(KEY_PENDING_TOKEN, token).apply()
        appScope.launch { sync(repository) }
    }

    suspend fun unregister(repository: TacRepository) {
        val registered = preferences.getString(KEY_REGISTERED_TOKEN, null)
        val pending = preferences.getString(KEY_PENDING_TOKEN, null)
        val token = registered ?: pending
        if (!token.isNullOrBlank()) {
            repository.unregisterFcmToken(token)
        }
        preferences.edit().remove(KEY_REGISTERED_TOKEN).remove(KEY_PENDING_TOKEN).apply()
    }

    private suspend fun currentToken(): String? {
        val pending = preferences.getString(KEY_PENDING_TOKEN, null)
        if (!pending.isNullOrBlank()) return pending
        return runCatching { FirebaseMessaging.getInstance().token.await() }
            .getOrNull()
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
