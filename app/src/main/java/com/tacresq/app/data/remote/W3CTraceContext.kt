package com.tacresq.app.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.security.SecureRandom

/**
 * Creates a fresh W3C version-00 trace context per HTTP request.
 * Trace context is observability metadata only; it never identifies or authorizes a user.
 */
internal object W3CTraceContext {
    private const val HEX = "0123456789abcdef"
    private val random = SecureRandom()

    fun newTraceparent(): String =
        "00-${randomNonZeroHex(16)}-${randomNonZeroHex(8)}-01"

    private fun randomNonZeroHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        do {
            random.nextBytes(bytes)
        } while (bytes.all { it.toInt() == 0 })

        return buildString(byteCount * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }
}

internal class TraceparentInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("traceparent", W3CTraceContext.newTraceparent())
            .build()
        return chain.proceed(request)
    }
}
