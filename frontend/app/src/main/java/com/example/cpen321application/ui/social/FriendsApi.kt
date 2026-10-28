package com.example.cpen321application.ui.social

import retrofit2.Response
import retrofit2.http.*

data class FriendProfile(val id: String, val displayName: String?, val avatarUrl: String?) {
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: "Trader"
}
data class Friendship(val friendshipId: String, val user: FriendProfile)
data class FriendRequest(val id: String, val sender: FriendProfile, val recipient: FriendProfile)
data class FriendsResponse(val friends: List<Friendship>)
data class RequestsResponse(val incoming: List<FriendRequest>, val outgoing: List<FriendRequest>)
data class MeResponse(val user: FriendProfile)
data class AddFriendRequest(val userId: String)
data class SendFriendResponse(val request: FriendRequest)
data class LeaderboardEntry(val user: FriendProfile, val isYou: Boolean,
    val cashBalance: String?, val returnPercent: Double?)
data class LeaderboardResponse(val entries: List<LeaderboardEntry>)

interface FriendsApi {
    @GET("api/friends/leaderboard")
    suspend fun leaderboard(@Header("Authorization") auth: String): Response<LeaderboardResponse>
    @POST("api/auth/me")
    suspend fun me(@Header("Authorization") auth: String): Response<MeResponse>
    @GET("api/friends")
    suspend fun friends(@Header("Authorization") auth: String): Response<FriendsResponse>
    @GET("api/friends/requests")
    suspend fun requests(@Header("Authorization") auth: String): Response<RequestsResponse>
    @POST("api/friends/requests")
    suspend fun send(@Header("Authorization") auth: String, @Body body: AddFriendRequest): Response<SendFriendResponse>
    @POST("api/friends/requests/{id}/accept")
    suspend fun accept(@Header("Authorization") auth: String, @Path("id") id: String): Response<Unit>
    @DELETE("api/friends/requests/{id}")
    suspend fun deleteRequest(@Header("Authorization") auth: String, @Path("id") id: String): Response<Unit>
    @DELETE("api/friends/{id}")
    suspend fun remove(@Header("Authorization") auth: String, @Path("id") id: String): Response<Unit>
}
