package com.tacresq.app.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tacresq.app.MainActivity
import com.tacresq.app.TacResqApp

/** Receives minimal event data; incident details are fetched only after the user opens the app. */
class TacResqFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val app = application as? TacResqApp ?: return
        app.container.pushTokenCoordinator.onTokenRefreshed(token, app.container.repository)
    }

    @SuppressLint("MissingPermission")
    override fun onMessageReceived(message: RemoteMessage) {
        PushNotificationChannels.ensure(this)
        if (!PushTokenCoordinator.notificationsAllowed(this)) return

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INCIDENT_ID, message.data["incident_id"])
            putExtra(EXTRA_EVENT_TYPE, message.data["event_type"])
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            message.data["incident_id"]?.hashCode() ?: 0,
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
        NotificationManagerCompat.from(this).notify(
            message.data["incident_id"]?.hashCode() ?: System.currentTimeMillis().toInt(),
            notification,
        )
    }

    companion object {
        const val EXTRA_INCIDENT_ID = "tacresq.incident_id"
        const val EXTRA_EVENT_TYPE = "tacresq.event_type"
    }
}
