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
    fun managerTagPreservesTheFullNotificationIdentity() {
        val first = PushNotificationIdentity.notificationManagerTag("inbox-101")
        val same = PushNotificationIdentity.notificationManagerTag("inbox-101")
        val second = PushNotificationIdentity.notificationManagerTag("inbox-102")

        assertEquals("inbox-101", first)
        assertEquals(first, same)
        assertNotEquals(first, second)
    }

    @Test
    fun notificationManagerTagRejectsMalformedIds() {
        assertTrue(runCatching {
            PushNotificationIdentity.notificationManagerTag("../injected")
        }.isFailure)
    }
}
