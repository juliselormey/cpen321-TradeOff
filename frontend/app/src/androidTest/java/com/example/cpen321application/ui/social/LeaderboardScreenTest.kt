package com.example.cpen321application.ui.social

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import retrofit2.Response
import java.lang.reflect.Proxy

class LeaderboardScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun api(hang: Boolean): FriendsApi {
        val unused = Proxy.newProxyInstance(
        FriendsApi::class.java.classLoader, arrayOf(FriendsApi::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "Test leaderboard API"
            else -> error("Unexpected request ${method.name}")
        }
    } as FriendsApi
        return object : FriendsApi by unused {
            override suspend fun leaderboard(auth: String): Response<LeaderboardResponse> {
                if (hang) delay(10_000)
                return Response.success(LeaderboardResponse(listOf(
                LeaderboardEntry(FriendProfile("me", "Test Me", null), true, "10000", 0.0, "10000"),
                LeaderboardEntry(FriendProfile("friend", "Test Friend", null), false, "0", 10.0, "11000"),
            )))
            }
        }
    }

    @Test fun displaysFriendAndSelfAfterSuccessfulRequest() {
        val loader = LeaderboardLoader(api(false), { "test" })
        compose.setContent { FriendsLeaderboard(Modifier.fillMaxSize(), loader) }
        compose.waitUntil(5_000) { loader.state.value.loaded }
        compose.onNodeWithText("Test Friend").assertIsDisplayed()
        compose.onNodeWithText("$11,000.00").assertIsDisplayed()
        compose.onNodeWithText("Portfolio value").assertIsDisplayed()
        compose.onNodeWithText("Test Me (You)").assertIsDisplayed()
        compose.onNodeWithText("Loading leaderboard…").assertDoesNotExist()
    }

    @Test fun stalledRequestShowsRetryAndStopsLoading() {
        val loader = LeaderboardLoader(api(true), { "test" }, timeoutMs = 200)
        compose.setContent { FriendsLeaderboard(Modifier.fillMaxSize(), loader) }
        compose.waitUntil(5_000) { loader.state.value.error != null }
        compose.onNodeWithText("Leaderboard timed out. Tap Refresh to retry.").assertIsDisplayed()
        compose.onNodeWithText("Loading leaderboard…").assertDoesNotExist()
        compose.onNodeWithText("Refresh").assertIsEnabled()
    }
}
