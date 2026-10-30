package com.tacresq.app.data.repo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingIncidentHttpPolicyTest {
    @Test
    fun authenticationAndRecoverableResponsesPreservePendingSos() {
        listOf(401, 403, 408, 409, 429).forEach { status ->
            assertFalse("HTTP $status must preserve the saved SOS", isDefinitiveIncidentRejection(status))
        }
    }

    @Test
    fun definitiveClientRejectionsMayClearPendingSos() {
        listOf(400, 404, 422).forEach { status ->
            assertTrue("HTTP $status definitively rejects the submission", isDefinitiveIncidentRejection(status))
        }
    }

    @Test
    fun serverErrorsAreNotTreatedAsDefinitiveRejections() {
        listOf(500, 502, 503, 504).forEach { status ->
            assertFalse("HTTP $status may be transient", isDefinitiveIncidentRejection(status))
        }
    }
}
