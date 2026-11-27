package com.tacresq.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class W3CTraceContextTest {
    @Test
    fun generatedTraceparentUsesVersionZeroAndNonZeroIdentifiers() {
        val value = W3CTraceContext.newTraceparent()
        val match = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-01$").matchEntire(value)

        assertTrue("Expected a valid version-00 traceparent", match != null)
        assertNotEquals("Trace ID must not be all zeroes", "0".repeat(32), match!!.groupValues[1])
        assertNotEquals("Span ID must not be all zeroes", "0".repeat(16), match.groupValues[2])
    }

    @Test
    fun everyHttpRequestGetsFreshTraceContext() {
        val first = W3CTraceContext.newTraceparent()
        val second = W3CTraceContext.newTraceparent()

        assertNotEquals(first, second)
    }
}
