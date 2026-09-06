package com.aegis.vpnclient.network

import com.aegis.vpnclient.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface AegisApi {
    @POST("/api/client/login")
    suspend fun login(@Body body: LoginRequest): Response<AccessResponse>

    @POST("/api/client/signup")
    suspend fun signup(@Body body: SignupRequest): Response<AccessResponse>

    /** Called twice by design — once right after the tunnel comes up, then periodically as a heartbeat. */
    @POST("/api/client/verify")
    suspend fun verify(@Header("Authorization") bearerToken: String): Response<VerifyResponse>
}

object ApiClient {
    val instance: AegisApi by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AegisApi::class.java)
    }
}
