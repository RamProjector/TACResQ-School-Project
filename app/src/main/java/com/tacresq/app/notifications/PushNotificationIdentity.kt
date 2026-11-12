package com.tacresq.app.notifications

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/**
 * Gives each inbox notification a stable Android identity.
 *
 * FCM data is untrusted input. Prefer the server's inbox notification ID, then
 * the FCM message ID; generate a last-resort ID only for malformed/legacy data.
 */
internal object PushNotificationIdentity {
    private val validId = Regex("[A-Za-z0-9._:-]{1,200}")

    fun resolveNotificationId(data: Map<String, String>, messageId: String?, generatedId: String = UUID.randomUUID().toString()): String {
        return data["notification_id"]?.takeIf(validId::matches)
            ?: messageId?.takeIf(validId::matches)
            ?: generatedId.takeIf(validId::matches)
            ?: UUID.randomUUID().toString()
    }

    /** Android notification IDs are Ints; SHA-256 avoids Java String.hashCode collisions. */
    fun notificationManagerId(notificationId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256").digest(notificationId.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int and Int.MAX_VALUE).coerceAtLeast(1)
    }
}
