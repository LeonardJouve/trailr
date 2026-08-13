package ch.trailer.android.api

import kotlinx.serialization.json.Json
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object NetworkModule {
    private const val BASE_URL = "http://192.168.1.249:3000/"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    val client = OkHttpClient.Builder()
        .connectTimeout(180, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val trailApi: TrailApi = retrofit.create(TrailApi::class.java)
}