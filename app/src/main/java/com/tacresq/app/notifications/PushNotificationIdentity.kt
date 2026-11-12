package com.tacresq.app.notifications

import java.util.UUID

/**
 * Gives each inbox notification a stable Android identity.
 *
 * FCM data is untrusted input. Prefer the server's inbox notification ID, then
 * the FCM message ID; generate a last-resort ID only for malformed/legacy data.
 */
internal object PushNotificationIdentity {
    private val validId = Regex("[A-Za-z0-9._:-]{1,200}")

    fun isValidNotificationId(value: String): Boolean = validId.matches(value)

    fun resolveNotificationId(
        data: Map<String, String>,
        messageId: String?,
        generatedId: String = UUID.randomUUID().toString(),
    ): String {
        return data["notification_id"]?.takeIf(::isValidNotificationId)
            ?: messageId?.takeIf(::isValidNotificationId)
            ?: generatedId.takeIf(::isValidNotificationId)
            ?: UUID.randomUUID().toString()
    }

    /**
     * Android notification identity is the (tag, integer ID) pair. Keeping the
     * complete server ID in the tag avoids truncating it into a collision-prone Int.
     */
    fun notificationManagerTag(notificationId: String): String {
        require(isValidNotificationId(notificationId)) { "Notification ID is invalid." }
        return notificationId
    }
}
