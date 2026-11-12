package com.tacresq.app.notifications

import android.content.Intent

/**
 * A push tap is only a locator for the authenticated notification inbox.
 * It is never treated as proof that the current user may access an incident.
 */
data class PushNotificationTap(val notificationId: String) {
    companion object {
        fun from(intent: Intent?): PushNotificationTap? {
            val id = intent?.getStringExtra(TacResqFirebaseMessagingService.EXTRA_NOTIFICATION_ID)
                ?.takeIf { it.isNotBlank() && it.length <= 200 }
                ?: return null
            return PushNotificationTap(id)
        }
    }
}
