package com.example.cpen321application

import retrofit2.Call
import retrofit2.http.GET
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST


interface ApiService {
    @POST("/api/auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleAuthRequest): Response<GoogleAuthResponse>
}

data class ServerIpResponse(val ip: String, val clientIp: String)
data class ServerTimeResponse(val time: String)
data class NameResponse(val firstName: String, val lastName: String)

data class GoogleAuthRequest(val idToken: String)
data class GoogleAuthResponse(val accessToken: String)