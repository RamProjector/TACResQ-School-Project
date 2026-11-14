package com.tacresq.app.notifications

import com.tacresq.app.data.model.SessionState

/**
 * Prevents an installation that has switched accounts from displaying a push
 * claimed for its previous account. Missing or untrusted identity fails closed.
 */
internal object PushNotificationRecipientPolicy {
    fun shouldDisplay(recipientProfileId: String?, sessionState: SessionState): Boolean {
        val expectedProfileId = recipientProfileId
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return false
        val currentProfileId = (sessionState as? SessionState.LoggedIn)
            ?.session
            ?.user
            ?.id
            ?: return false
        return currentProfileId == expectedProfileId
    }
}
