package com.tacresq.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TacRepositoryErrorPolicyTest {
    @Test
    fun onlyClearlyDefinitiveClientRejectionsCanBeDiscarded() {
        assertFalse(isDefinitiveIncidentRejection(400))
        assertFalse(isDefinitiveIncidentRejection(401))
        assertFalse(isDefinitiveIncidentRejection(403))
        assertFalse(isDefinitiveIncidentRejection(408))
        assertFalse(isDefinitiveIncidentRejection(409))
        assertFalse(isDefinitiveIncidentRejection(429))
        assertTrue(isDefinitiveIncidentRejection(422))
        assertFalse(isDefinitiveIncidentRejection(500))
    }

    @Test
    fun signInErrorsDoNotMislabelServiceFailuresAsBadCredentials() {
        assertEquals(
            "Email or password is incorrect.",
            signInHttpErrorMessage(400),
        )
        assertEquals(
            "Email or password is incorrect.",
            signInHttpErrorMessage(401),
        )
        assertEquals(
            "Too many sign-in attempts. Wait a moment and try again.",
            signInHttpErrorMessage(429),
        )
        assertEquals(
            "The sign-in service is temporarily unavailable. Please try again.",
            signInHttpErrorMessage(503),
        )
        assertEquals(
            "Sign-in couldn't be completed. Check your email confirmation or try again.",
            signInHttpErrorMessage(422),
        )
    }
}
