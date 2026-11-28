package com.tacresq.app.data.remote

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

    @Test
    fun interceptorAddsFreshValidTraceparentAndReplacesCallerValue() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()

        try {
            val client = OkHttpClient.Builder()
                .addInterceptor(TraceparentInterceptor())
                .build()
            val supplied = Request.Builder()
                .url(server.url("/first"))
                .header(
                    "traceparent",
                    "00-11111111111111111111111111111111-2222222222222222-01",
                )
                .build()

            client.newCall(supplied).execute().use { response ->
                assertTrue(response.isSuccessful)
            }
            client.newCall(
                Request.Builder().url(server.url("/second")).build(),
            ).execute().use { response ->
                assertTrue(response.isSuccessful)
            }

            val first = server.takeRequest().getHeader("traceparent")
            val second = server.takeRequest().getHeader("traceparent")
            val pattern = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-01$")

            assertTrue("First request must carry a valid traceparent", pattern.matches(first.orEmpty()))
            assertTrue("Second request must carry a valid traceparent", pattern.matches(second.orEmpty()))
            assertNotEquals("Caller-supplied trace context must be replaced", "00-11111111111111111111111111111111-2222222222222222-01", first)
            val firstMatch = pattern.matchEntire(first.orEmpty())!!
            val secondMatch = pattern.matchEntire(second.orEmpty())!!
            assertNotEquals("Separate requests must not reuse the same trace ID", firstMatch.groupValues[1], secondMatch.groupValues[1])
            assertNotEquals("Separate requests must not reuse the same span ID", firstMatch.groupValues[2], secondMatch.groupValues[2])
        } finally {
            server.shutdown()
        }
    }
}
