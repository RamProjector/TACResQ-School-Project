package com.tacresq.app.data.routing

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Rejects oversized routing payloads before Retrofit/Gson parses them.
 *
 * Content-Length is an early rejection hint only. Calling request(max + 1) also
 * bounds unknown-length and chunked bodies, including transparent gzip responses,
 * to at most maxResponseBytes + 1 buffered bytes before rejection.
 */
internal class RoutingResponseSizeInterceptor(
    private val maxResponseBytes: Long,
) : Interceptor {
    init {
        require(maxResponseBytes > 0L) { "Response size limit must be positive." }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response

        if (body.contentLength() > maxResponseBytes) {
            response.close()
            throw IOException("Routing response exceeded the configured size limit.")
        }

        val source = body.source()
        if (source.request(maxResponseBytes + 1L)) {
            response.close()
            throw IOException("Routing response exceeded the configured size limit.")
        }

        return response
    }
}
