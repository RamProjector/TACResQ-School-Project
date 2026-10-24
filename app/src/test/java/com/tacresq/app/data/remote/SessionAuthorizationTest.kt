package com.tacresq.app.data.remote

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionAuthorizationTest {
    private val request = Request.Builder()
        .url("https://example.test/bridge/rpc/api_me")
        .build()

    @Test
    fun explicitFreshTokenIsPreservedWhenStoredTokenIsStale() {
        val freshRequest = request.newBuilder()
            .header("Authorization", "Bearer fresh-session-token")
            .build()

        val result = withStoredSessionAuthorization(freshRequest) {
            "stale-session-token"
        }

        assertEquals("Bearer fresh-session-token", result.header("Authorization"))
    }

    @Test
    fun storedTokenIsAddedWhenRequestHasNoAuthorizationHeader() {
        val result = withStoredSessionAuthorization(request) {
            "stored-session-token"
        }

        assertEquals("Bearer stored-session-token", result.header("Authorization"))
    }

    @Test
    fun providerIsNotCalledWhenExplicitAuthorizationAlreadyExists() {
        var providerCalled = false
        val explicitRequest = request.newBuilder()
            .header("Authorization", "Bearer explicit-token")
            .build()

        val result = withStoredSessionAuthorization(explicitRequest) {
            providerCalled = true
            "stored-session-token"
        }

        assertFalse(providerCalled)
        assertEquals("Bearer explicit-token", result.header("Authorization"))
    }

    @Test
    fun blankStoredTokenDoesNotCreateAnAuthorizationHeader() {
        val result = withStoredSessionAuthorization(request) { "   " }

        assertNull(result.header("Authorization"))
    }
}
