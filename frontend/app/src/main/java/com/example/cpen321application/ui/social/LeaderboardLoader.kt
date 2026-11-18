package com.example.cpen321application.ui.social

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

internal data class LeaderboardState(
    val entries: List<LeaderboardEntry> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

// Keep asynchronous request state outside composition so UI redraws cannot
// interfere with request completion. Every request has a bounded lifetime.
internal class LeaderboardLoader(
    private val api: FriendsApi,
    private val token: () -> String?,
    private val timeoutMs: Long = 18_000,
) {
    private val mutableState = MutableStateFlow(LeaderboardState())
    val state = mutableState.asStateFlow()

    suspend fun refresh() {
        if (mutableState.value.loading) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        try {
            val accessToken = token()
            if (accessToken.isNullOrBlank()) {
                mutableState.value = LeaderboardState(error = friendsError(401))
                return
            }
            val response = withTimeout(timeoutMs) { api.leaderboard("Bearer $accessToken") }
            val body = response.body()
            mutableState.value = if (response.isSuccessful && body != null)
                LeaderboardState(entries = body.entries, loaded = true)
            else LeaderboardState(error = if (response.code() == 404)
                "The leaderboard update needs to be deployed to the server." else friendsError(response.code()))
        } catch (_: TimeoutCancellationException) {
            mutableState.value = mutableState.value.copy(error = "Leaderboard timed out. Tap Refresh to retry.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "Couldn't load rankings. Check your connection and tap Refresh.")
        } finally {
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }
}
