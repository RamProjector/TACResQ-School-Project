package com.tacresq.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthCallbackMessageTest {
    @Test
    fun acceptsExactCallbackAndIgnoresTokens() {
        assertEquals(
            AuthCallbackMessage.MESSAGE,
            AuthCallbackMessage.from("tacresq://auth/callback#access_token=untrusted&token_type=bearer"),
        )
    }

    @Test
    fun rejectsOtherSchemesHostsAndPaths() {
        assertNull(AuthCallbackMessage.from("https://example.com/auth/callback"))
        assertNull(AuthCallbackMessage.from("tacresq://other/callback"))
        assertNull(AuthCallbackMessage.from("tacresq://auth/other"))
        assertNull(AuthCallbackMessage.from("tacresq://user@auth/callback"))
        assertNull(AuthCallbackMessage.from("tacresq://auth:123/callback"))
    }

    @Test
    fun rejectsMissingAndMalformedUris() {
        assertNull(AuthCallbackMessage.from(null))
        assertNull(AuthCallbackMessage.from(" "))
        assertNull(AuthCallbackMessage.from("tacresq://auth/callback%ZZ"))
    }
}
