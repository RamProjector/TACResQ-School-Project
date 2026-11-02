package com.tacresq.app.data.routing

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

internal object RenderRoutingClientFactory {
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 70L
    private const val WRITE_TIMEOUT_SECONDS = 10L
    // Leave time for the existing free Render service to wake after an idle period.
    private const val CALL_TIMEOUT_SECONDS = 75L
    private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L

    fun create(baseUrl: String, tokenProvider: () -> String?): RoutingProvider {
        val url = baseUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Routing API root must be a valid HTTPS URL.")
        require(url.isHttps) { "Routing API root must use HTTPS." }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Routing API root must not embed credentials."
        }
        require(url.query == null && url.fragment == null) {
            "Routing API root must not contain a query or fragment."
        }
        require(baseUrl.trim().endsWith("/")) {
            "Routing API root must end with '/' for Retrofit."
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor(RoutingResponseSizeInterceptor(MAX_RESPONSE_BYTES))
            .build()
        val service = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RenderRoutingService::class.java)
        return RenderRoutingProvider(service, tokenProvider)
    }
}
