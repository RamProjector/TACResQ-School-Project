package com.tacresq.app.data.routing

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RoutingResponseSizeInterceptorTest {
    @Test
    fun acceptsResponseAtOrBelowLimit() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"route":"ok"}"""))
            val client = clientWithLimit(64L)

            client.newCall(Request.Builder().url(server.url("/route")).build()).execute().use { response ->
                assertEquals(200, response.code)
                assertEquals("""{"route":"ok"}""", response.body?.string())
            }
        }
    }

    @Test
    fun rejectsOversizedContentLengthBeforeParsing() {
        assertOversizedResponse(
            MockResponse().setBody("x".repeat(65)),
        )
    }

    @Test
    fun rejectsOversizedChunkedBodyWithoutContentLength() {
        assertOversizedResponse(
            MockResponse().setChunkedBody("x".repeat(65), 7),
        )
    }

    @Test
    fun rejectsNonPositiveLimit() {
        try {
            RoutingResponseSizeInterceptor(0L)
            fail("Expected non-positive size limit to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun assertOversizedResponse(response: MockResponse) {
        MockWebServer().use { server ->
            server.enqueue(response)
            try {
                clientWithLimit(64L)
                    .newCall(Request.Builder().url(server.url("/route")).build())
                    .execute()
                    .use { fail("Expected oversized response to fail closed") }
            } catch (expected: IOException) {
                // The interceptor rejects oversized responses as transport failures.
            }
        }
    }

    private fun clientWithLimit(limit: Long): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(RoutingResponseSizeInterceptor(limit))
            .build()
}
