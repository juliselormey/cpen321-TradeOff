package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.cpen321application.ApiClient
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import com.example.cpen321application.ui.social.LeaderboardEntry
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** View Leaderboard, formal specification 3.5.5. Real HTTP, database and prices. */
class LeaderboardE2ETest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private var prepared = false

    private fun fixture(path: String, body: String? = "{}"): String {
        val builder = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/__e2e/" + path)
            .header("Authorization", "Bearer " + requireNotNull(SessionManager.getToken(compose.activity)))
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        client.newCall(builder.build()).execute().use {
            check(it.isSuccessful) { "Leaderboard fixture HTTP ${it.code}; check E2E_ENABLED, server and Google login." }
            return requireNotNull(it.body).string()
        }
    }

    @Before fun prepareIndependentAccount() {
        check(SessionManager.isLoggedIn(compose.activity)) { "Sign in through Google and return to Market." }
        check(BuildConfig.API_BASE_URL.trimEnd('/') == BuildConfig.MARKET_API_BASE_URL.trimEnd('/'))
        fixture("account/reset")
        prepared = true
        // Many participant records are prepared in the real database automatically.
        // Clear this test account's relationships so success 9 never depends on old friends.
        fixture("leaderboard/setup", "{\"empty\":true}")
        compose.activityRule.scenario.recreate()
        waitFor("Market")
    }

    @After fun cleanAccount() {
        if (prepared) {
            try { fixture("leaderboard/cleanup") } finally { fixture("account/reset") }
        }
    }

    private fun waitFor(text: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun entries(): List<LeaderboardEntry> = runBlocking {
        val response = ApiClient.createFriends(compose.activity).leaderboard(
            "Bearer " + requireNotNull(SessionManager.getToken(compose.activity)))
        check(response.isSuccessful) { "Leaderboard HTTP ${response.code()}" }
        requireNotNull(response.body()).entries
    }

    private fun openLeaderboard() {
        // Success 1-2: actual navigation from the signed-in main screen.
        compose.onNode(hasText("Social") and hasClickAction()).performClick()
        waitFor("Friends leaderboard")
        compose.onNodeWithText("Leaderboard").performClick()
        waitFor("Portfolio value")
    }

    private fun verifyRanking(expected: List<LeaderboardEntry>) {
        expected.forEachIndexed { index, entry ->
            val tag = "leaderboard-row-${entry.user.id}"
            compose.onNodeWithTag("leaderboard-list").performScrollToNode(hasTestTag(tag))
            val row = compose.onNodeWithTag(tag)
            row.assertIsDisplayed().assertTextContains("${index + 1}")
                .assertTextContains(entry.user.label + if (entry.isYou) " (You)" else "")
                .assertTextContains(NumberFormat.getCurrencyInstance(Locale.US)
                    .format(requireNotNull(entry.totalPortfolioValue).toBigDecimal()))
                .assertTextContains(String.format(Locale.US, "%+.1f%% total return", requireNotNull(entry.returnPercent)))
        }
    }

    @Test fun viewLeaderboardRanksAcceptedFriendsByValueAndReturnWithoutMutatingAccounts() {
        val emptyBefore = fixture("leaderboard/snapshot", null)
        openLeaderboard()
        // Success 9: without accepted friends only the Trader appears.
        waitFor("Accept a friend request to compare with friends.")
        val alone = entries()
        assertEquals(1, alone.size)
        assertTrue(alone.single().isYou)
        verifyRanking(alone)
        assertEquals("Viewing the self-only leaderboard must not change data", emptyBefore,
            fixture("leaderboard/snapshot", null))

        // Automated independent setup: six accepted friends (both directions),
        // one pending request and one unrelated user. No second interactive login.
        compose.onNodeWithText("Back to Market").performClick()
        fixture("leaderboard/setup")
        val before = fixture("leaderboard/snapshot", null)
        val loaded = entries()
        assertEquals("Only self and six accepted friends belong in the ranking", 7, loaded.size)
        assertEquals(1, loaded.count { it.isYou })
        assertEquals((1..6).map { "E2E Trader $it" }.toSet(), loaded.filterNot { it.isYou }.map { it.user.label }.toSet())
        val byValue = loaded.sortedWith(compareByDescending<LeaderboardEntry> {
            requireNotNull(it.totalPortfolioValue).toBigDecimal()
        }.thenBy { it.user.id })
        val byReturn = loaded.sortedWith(compareByDescending<LeaderboardEntry> {
            requireNotNull(it.returnPercent)
        }.thenBy { it.user.id })
        assertNotEquals("Fixture must exercise a real reorder", byValue.map { it.user.id }, byReturn.map { it.user.id })
        openLeaderboard()
        // Success 3-5: backend values include the friend's real MSFT holding.
        // Show all to inspect every row, including entries outside the default top five.
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Accept a friend request to compare with friends.").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("leaderboard-row-${byValue.first().user.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("See all").performClick()
        verifyRanking(byValue)
        // Success 6-8: select percentage return; verify every rank/value/return.
        compose.onNodeWithText("Portfolio value").performClick()
        waitFor("Return %")
        verifyRanking(byReturn)
        // Return to the original criterion and exercise a normal live reload.
        compose.onNodeWithText("Return %").performClick()
        compose.onNodeWithText("Refresh").assertIsEnabled().performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Refresh") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        verifyRanking(byValue)
        // Postconditions: reading/sorting/reloading must leave all participants'
        // balances, positions, trades, orders and friendships unchanged.
        assertEquals("Leaderboard must be read-only for every participant", before,
            fixture("leaderboard/snapshot", null))
        // Failure 3a-3a3: controlled provider/server failure is excluded under the
        // assignment's no-mocking rule, as documented in Testing.md.
    }
}
