package id.hanifalfaqih.aienglishinterview.core.network

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import id.hanifalfaqih.aienglishinterview.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Builds the shared Retrofit instance. Knows transports and JSON only —
 * nothing about API endpoints (those live in `data/remote`).
 */
object RetrofitFactory {

    val json = Json {
        // Tolerate server-added fields so the app survives backend evolution.
        ignoreUnknownKeys = true
        // Omit nulls on encode: the backend validates optional fields with
        // `optional()` (not `nullable()`), so an explicit null is rejected.
        explicitNulls = false
    }

    fun newRetrofit(baseUrl: String): Retrofit {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }
}
