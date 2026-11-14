package com.tacresq.app.notifications

import com.tacresq.app.data.model.Role
import com.tacresq.app.data.model.Session
import com.tacresq.app.data.model.SessionState
import com.tacresq.app.data.model.User
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushNotificationRecipientPolicyTest {
    private fun loggedIn(profileId: Long): SessionState = SessionState.LoggedIn(
        Session(
            token = "test-session-token",
            user = User(
                id = profileId,
                name = "Test User",
                email = "test@example.test",
                role = Role.REQUESTER,
            ),
        ),
    )

    @Test
    fun displaysOnlyForTheAccountThatOwnsTheNotification() {
        assertTrue(PushNotificationRecipientPolicy.shouldDisplay("101", loggedIn(101)))
        assertFalse(PushNotificationRecipientPolicy.shouldDisplay("101", loggedIn(202)))
    }

    @Test
    fun failsClosedWhenRecipientIdentityIsMissingOrMalformed() {
        assertFalse(PushNotificationRecipientPolicy.shouldDisplay(null, loggedIn(101)))
        assertFalse(PushNotificationRecipientPolicy.shouldDisplay("", loggedIn(101)))
        assertFalse(PushNotificationRecipientPolicy.shouldDisplay("0", loggedIn(101)))
        assertFalse(PushNotificationRecipientPolicy.shouldDisplay("-1", loggedIn(101)))
        assertFalse(PushNotificationRecipientPolicy.shouldDisplay("not-a-profile-id", loggedIn(101)))
    }

    @Test
    fun doesNotDisplayForLoggedOutOrUnrestoredSessions() {
        assertFalse(PushNotificationRecipientPolicy.shouldDisplay("101", SessionState.LoggedOut))
        assertFalse(PushNotificationRecipientPolicy.shouldDisplay("101", SessionState.RestoreFailed))
        assertFalse(PushNotificationRecipientPolicy.shouldDisplay("101", SessionState.Loading))
    }
}
