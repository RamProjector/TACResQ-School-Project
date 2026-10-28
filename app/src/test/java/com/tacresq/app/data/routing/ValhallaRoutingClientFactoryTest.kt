package com.tacresq.app.data.routing

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ValhallaRoutingClientFactoryTest {
    @Test
    fun missingEndpointLeavesRoutingDisabled() {
        assertNull(ValhallaRoutingClientFactory.createOrNull(null))
        assertNull(ValhallaRoutingClientFactory.createOrNull(""))
        assertNull(ValhallaRoutingClientFactory.createOrNull("   "))
    }

    @Test
    fun acceptsExplicitHttpsEndpoint() {
        assertNotNull(ValhallaRoutingClientFactory.createOrNull("https://routing.example.test/"))
    }

    @Test
    fun rejectsPlainHttpEndpoint() {
        assertInvalid("http://routing.example.test/")
    }

    @Test
    fun rejectsEmbeddedCredentials() {
        assertInvalid("https://user:password@routing.example.test/")
    }

    @Test
    fun rejectsQueryFragmentAndMissingTrailingSlash() {
        assertInvalid("https://routing.example.test/?token=not-a-secret")
        assertInvalid("https://routing.example.test/#route")
        assertInvalid("https://routing.example.test")
    }

    private fun assertInvalid(url: String) {
        try {
            ValhallaRoutingClientFactory.createOrNull(url)
            fail("Expected endpoint validation to reject: $url")
        } catch (_: IllegalArgumentException) {
            // Expected: unsafe or malformed endpoint must fail closed.
        }
    }
}
