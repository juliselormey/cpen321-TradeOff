package com.example.cpen321application

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST


interface ApiService {
    @POST("/api/auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleAuthRequest): Response<GoogleAuthResponse>
}


data class GoogleAuthRequest(val idToken: String)
data class GoogleAuthResponse(val accessToken: String)