package com.tacresq.app.location

import com.tacresq.app.data.repo.ApiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingHttpStatusTest {
    @Test
    fun recognizesRepositoryWrappedAuthorizationAndAssignmentRejections() {
        assertTrue(ApiException("session expired", 401).hasHttpStatus(401, 403))
        assertTrue(ApiException("role rejected", 403).hasHttpStatus(401, 403))
        assertTrue(ApiException("assignment removed", 404).hasHttpStatus(401, 403, 404, 422))
        assertTrue(ApiException("invalid assignment", 422).hasHttpStatus(401, 403, 404, 422))
    }

    @Test
    fun ignoresFailuresWithoutAnAuthorizationOrAssignmentStatus() {
        assertFalse(ApiException("temporary server error", 503).hasHttpStatus(401, 403, 404, 422))
        assertFalse(IllegalStateException("network unavailable").hasHttpStatus(401, 403, 404, 422))
        assertFalse(null.hasHttpStatus(401, 403, 404, 422))
    }
}
