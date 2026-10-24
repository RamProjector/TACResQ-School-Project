package com.tacresq.app.data.remote

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseTacApiAuthorizationTest {
    private val baseRequest = Request.Builder()
        .url("https://example.test/api/bridge/rpc/api_me")
        .build()

    @Test
    fun explicitFreshTokenIsNotOverwrittenByCachedSession() {
        val request = baseRequest.newBuilder()
            .header("Authorization", "Bearer fresh-login-token")
            .build()

        val result = withStoredSessionAuthorization(request) { "stale-cached-token" }

        assertEquals("Bearer fresh-login-token", result.header("Authorization"))
    }

    @Test
    fun cachedSessionTokenIsAddedWhenRequestHasNoAuthorizationHeader() {
        val result = withStoredSessionAuthorization(baseRequest) { "cached-session-token" }

        assertEquals("Bearer cached-session-token", result.header("Authorization"))
    }

    @Test
    fun missingOrBlankCachedTokenDoesNotInventAuthorization() {
        assertNull(withStoredSessionAuthorization(baseRequest) { null }.header("Authorization"))
        assertNull(withStoredSessionAuthorization(baseRequest) { "   " }.header("Authorization"))
    }
}
