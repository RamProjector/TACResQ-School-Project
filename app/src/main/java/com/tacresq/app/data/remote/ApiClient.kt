package com.tacresq.app.data.remote

import com.tacresq.app.AppConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    fun create(tokenProvider: () -> String?): TacApi {
        val auth = okhttp3.Interceptor { chain ->
            val builder = chain.request().newBuilder().header("Accept", "application/json")
            tokenProvider()?.let { token -> builder.header("Authorization", "Bearer $token") }
            chain.proceed(builder.build())
        }

        // BASIC logs only the request line and status code, never bodies or tokens.
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(AppConfig.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TacApi::class.java)
    }
}
