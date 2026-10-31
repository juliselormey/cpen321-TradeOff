package com.example.cpen321application

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Header


interface ApiService {
    @GET("/api/users/me")
    suspend fun profile(@Header("Authorization") authorization: String): Response<ProfileResponse>
    @PATCH("/api/users/me")
    suspend fun updateProfile(@Header("Authorization") authorization: String,
        @Body profile: ProfileUpdate): Response<ProfileResponse>

    @POST("/api/auth/google")
    suspend fun loginWithGoogle(@Body request: GoogleAuthRequest): Response<GoogleAuthResponse>
}


data class GoogleAuthRequest(val idToken: String)
data class GoogleAuthResponse(val accessToken: String)
data class UserProfile(val id: String, val email: String, val displayName: String?, val username: String?)
data class ProfileResponse(val user: UserProfile)
data class ProfileUpdate(val displayName: String, val username: String)
