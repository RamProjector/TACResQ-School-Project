package com.tacresq.app.data.routing

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the routing adapter only when an explicit HTTPS endpoint is configured.
 *
 * The endpoint is deployment configuration, not a secret. Never put provider keys
 * in this URL or in the Android app; route hosting/authentication belongs server-side.
 * A missing endpoint deliberately leaves routing disabled.
 */
internal object ValhallaRoutingClientFactory {
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 10L
    private const val WRITE_TIMEOUT_SECONDS = 10L
    private const val CALL_TIMEOUT_SECONDS = 15L
    private const val MAX_RESPONSE_BYTES = 2L * 1024L * 1024L

    fun createOrNull(baseUrl: String?): RoutingProvider? {
        if (baseUrl.isNullOrBlank()) return null

        val url = baseUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Routing endpoint must be a valid HTTPS URL.")
        require(url.isHttps) { "Routing endpoint must use HTTPS." }
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Routing endpoint must not embed credentials."
        }
        require(url.query == null && url.fragment == null) {
            "Routing endpoint must not contain a query or fragment."
        }
        require(url.encodedPath.endsWith("/")) {
            "Routing endpoint must end with '/' for Retrofit."
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
            .create(ValhallaRoutingService::class.java)

        return ValhallaRoutingProvider(service)
    }
}
