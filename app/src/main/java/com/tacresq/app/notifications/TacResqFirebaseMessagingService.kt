package com.tacresq.app.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tacresq.app.MainActivity
import com.tacresq.app.TacResqApp
import com.tacresq.app.data.model.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Receives minimal event data; incident details are fetched only after the user opens the app. */
class TacResqFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = application as? TacResqApp ?: return
        app.container.pushTokenCoordinator.onTokenRefreshed(token, app.container.repository)
    }

    @SuppressLint("MissingPermission")
    override fun onMessageReceived(message: RemoteMessage) {
        if (!PushTokenCoordinator.notificationsAllowed(this)) return

        val app = application as? TacResqApp ?: return
        val recipientProfileId = message.data["recipient_profile_id"] ?: return
        // FCM may start the process before DataStore has restored the encrypted session.
        // Wait briefly for a definitive state, then fail closed: never display a push
        // whose intended account cannot be matched to the currently authenticated user.
        val sessionState = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(SESSION_RESTORE_TIMEOUT_MILLIS) {
                app.container.repository.sessionState.first { it !is SessionState.Loading }
            }
        } ?: return
        if (!PushNotificationRecipientPolicy.shouldDisplay(recipientProfileId, sessionState)) return

        PushNotificationChannels.ensure(this)
        val notificationId = PushNotificationIdentity.resolveNotificationId(message.data, message.messageId)
        val notificationTag = PushNotificationIdentity.notificationManagerTag(notificationId)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            // PendingIntent identity includes Intent data; extras alone do not distinguish alerts.
            data = Uri.Builder()
                .scheme("tacresq")
                .authority("notification")
                .appendPath(notificationId)
                .build()
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            putExtra(EXTRA_INCIDENT_ID, message.data["incident_id"])
            putExtra(EXTRA_EVENT_TYPE, message.data["event_type"])
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, PushNotificationChannels.ALERTS_CHANNEL)
            .setSmallIcon(com.tacresq.app.R.drawable.ic_tacresq_emergency_launcher)
            .setContentTitle("TACResQ alert")
            .setContentText("Open TACResQ to review a new incident update.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(this).notify(notificationTag, 0, notification)
    }

    companion object {
        private const val SESSION_RESTORE_TIMEOUT_MILLIS = 5_000L
        const val EXTRA_NOTIFICATION_ID = "tacresq.notification_id"
        const val EXTRA_INCIDENT_ID = "tacresq.incident_id"
        const val EXTRA_EVENT_TYPE = "tacresq.event_type"
    }
}
