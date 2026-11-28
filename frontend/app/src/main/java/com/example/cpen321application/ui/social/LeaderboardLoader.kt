package com.example.cpen321application.ui.social

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.cpen321application.ui.market.data.MarketStream
import com.example.cpen321application.ui.market.data.observeMarketRefreshes

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
    private var marketGeneration = 0L
    private var marketConnected = true
    private val refreshLock = Mutex()

    suspend fun observeMarket(stream: MarketStream) = observeMarketRefreshes(stream,
        onSnapshot = { marketGeneration++; marketConnected = true },
        onDisconnect = {
            marketGeneration++
            marketConnected = false
            mutableState.value = mutableState.value.copy(
                entries = mutableState.value.entries.map { it.copy(totalPortfolioValue = null, returnPercent = null) },
                error = "Live market disconnected. Reconnecting…",
            )
        },
        refresh = { refresh() },
    )

    suspend fun refresh() = refreshLock.withLock {
        val generation = marketGeneration
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        try {
            val accessToken = token()
            if (accessToken.isNullOrBlank()) {
                mutableState.value = LeaderboardState(error = friendsError(401))
                return@withLock
            }
            val response = withTimeout(timeoutMs) { api.leaderboard("Bearer $accessToken") }
            if (generation != marketGeneration || !marketConnected) return@withLock
            val body = response.body()
            mutableState.value = if (response.isSuccessful && body != null)
                LeaderboardState(entries = body.entries, loaded = true)
            else LeaderboardState(error = if (response.code() == 404)
                "The leaderboard update needs to be deployed to the server." else friendsError(response.code()))
        } catch (_: TimeoutCancellationException) {
            if (generation != marketGeneration || !marketConnected) return@withLock
            mutableState.value = mutableState.value.copy(error = "Leaderboard timed out. Tap Refresh to retry.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (generation != marketGeneration || !marketConnected) return@withLock
            mutableState.value = mutableState.value.copy(error = "Couldn't load rankings. Check your connection and tap Refresh.")
        } finally {
            mutableState.value = mutableState.value.copy(loading = false)
        }
    }
}
