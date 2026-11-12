package com.tacresq.app.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotificationIdentityTest {
    @Test
    fun serverNotificationIdWinsOverIncidentAndMessageIdentity() {
        val id = PushNotificationIdentity.resolveNotificationId(
            mapOf("notification_id" to "inbox-101", "incident_id" to "incident-7"),
            "fcm-message-9",
            "generated",
        )

        assertEquals("inbox-101", id)
    }

    @Test
    fun malformedServerIdFallsBackToFcmMessageId() {
        val id = PushNotificationIdentity.resolveNotificationId(
            mapOf("notification_id" to "../invalid"),
            "fcm-message-9",
            "generated",
        )

        assertEquals("fcm-message-9", id)
    }

    @Test
    fun missingIdsUseGeneratedFallback() {
        val id = PushNotificationIdentity.resolveNotificationId(emptyMap(), null, "generated-1")

        assertEquals("generated-1", id)
    }

    @Test
    fun managerIdentityIsStableAndSeparatesDistinctInboxNotifications() {
        val first = PushNotificationIdentity.notificationManagerId("inbox-101")
        val same = PushNotificationIdentity.notificationManagerId("inbox-101")
        val second = PushNotificationIdentity.notificationManagerId("inbox-102")

        assertEquals(first, same)
        assertNotEquals(first, second)
        assertTrue(first > 0)
        assertTrue(second > 0)
    }
}
