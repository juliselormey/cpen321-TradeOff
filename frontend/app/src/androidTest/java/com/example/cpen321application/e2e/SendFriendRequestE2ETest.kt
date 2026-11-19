package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.ApiClient
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Send Friend Request, formal specification 3.5.4. Normal backend, real SQL. */
class SendFriendRequestE2ETest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private val api get() = ApiClient.createFriends(compose.activity)
    private val auth get() = "Bearer " + requireNotNull(SessionManager.getToken(compose.activity))
    private var prepared = false
    private lateinit var fixtures: JSONObject

    private fun fixture(path: String, get: Boolean = false): String {
        val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/__e2e/" + path)
            .header("Authorization", auth)
        if (!get) request.post("{}".toRequestBody("application/json".toMediaType()))
        return client.newCall(request.build()).execute().use {
            check(it.isSuccessful) { "Friend-request setup HTTP ${it.code}; check E2E_ENABLED and sign in again if 401." }
            requireNotNull(it.body).string()
        }
    }

    @Before fun prepareIndependentAccount() {
        check(SessionManager.isLoggedIn(compose.activity)) { "Sign in through Google and return to Market." }
        check(BuildConfig.API_BASE_URL.trimEnd('/') == BuildConfig.MARKET_API_BASE_URL.trimEnd('/'))
        fixture("account/reset")
        prepared = true
        // Reuse real multi-user database preparation, not another interactive login.
        fixtures = JSONObject(fixture("leaderboard/setup"))
        compose.activityRule.scenario.recreate()
        waitFor("Market")
    }

    @After fun cleanup() {
        if (prepared) {
            try { fixture("leaderboard/cleanup") } finally { fixture("account/reset") }
        }
    }

    private fun participant(index: Int) = fixtures.getJSONArray("users").getJSONObject(index).getString("id")
    private fun participantUsername(index: Int) = fixtures.getJSONArray("users").getJSONObject(index).getString("username")
    private fun snapshot() = fixture("account/snapshot", get = true)
    private fun waitFor(text: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun openFriends() {
        // Success 1-3: navigate to Social/Friends, wait for actual account data.
        compose.onNode(hasText("Social") and hasClickAction()).performClick()
        compose.onNodeWithText("Friends").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Add friend") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun addFriend(id: String) {
        // Success 4-6: open the form, enter the username and submit through the UI.
        compose.onNodeWithText("Add friend").performScrollTo().performClick()
        enterAndSend(id)
    }

    private fun enterAndSend(id: String) {
        compose.onNodeWithText("Friend username").performTextReplacement(id)
        closeSoftKeyboard()
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("Send request") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Send request").performClick()
    }

    private fun assertRejected(message: String, before: String) {
        waitFor(message)
        compose.onAllNodesWithText(message).onLast().assertIsDisplayed()
        assertEquals("Rejected friend request must not modify database state", before, snapshot())
    }

    @Test fun sendFriendRequestValidatesIdsAndRelationshipsThenPersistsOnePendingRequest() {
        val before = snapshot()
        val meResponse = runBlocking { api.me(auth) }
        check(meResponse.isSuccessful)
        val profile = requireNotNull(meResponse.body()).user
        val me = profile.id
        val myUsername = requireNotNull(profile.username)
        openFriends()
        // Success 3: existing accepted friends and pending requests are shown.
        compose.onNodeWithText("Outgoing requests (1)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("E2E Trader 7").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Friends (6)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("E2E Trader 1").performScrollTo().assertIsDisplayed()

        // Failure 7b: an accepted friendship already exists. Return to Friends.
        addFriend(participantUsername(0))
        assertRejected("You're already friends or have a pending request. Check incoming requests.", before)
        compose.onNodeWithText("Cancel").performClick()
        // Failure 7b also covers a pending outgoing request, without duplicating it.
        addFriend(participantUsername(6))
        assertRejected("You're already friends or have a pending request. Check incoming requests.", before)
        compose.onNodeWithText("Cancel").performClick()

        // Failure 7c-7c2: reject own username, then cancel the form.
        addFriend(myUsername)
        assertRejected("You cannot add yourself.", before)
        compose.onNodeWithText("Cancel").performClick()

        // Failure 7a-7a2: a syntactically valid but unknown username; correct it in place.
        addFriend("missing_" + UUID.randomUUID().toString().replace("-", "").take(16))
        assertRejected("This username or request was not found. Refresh and try again.", before)
        enterAndSend(participantUsername(7))
        // Success 7-9: actual production API creates the pending request and reloads UI.
        waitFor("Friend request sent.")
        compose.onNodeWithText("Friend request sent.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Outgoing requests (2)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("E2E Trader 8").performScrollTo().assertIsDisplayed()

        val result = runBlocking { api.requests(auth) }
        check(result.isSuccessful)
        val requests = requireNotNull(result.body())
        assertEquals(2, requests.outgoing.size)
        val sent = requests.outgoing.single { it.recipient.id == participant(7) }
        assertEquals(me, sent.sender.id)
        assertTrue(requests.incoming.isEmpty())
        val after = JSONObject(snapshot())
        val original = JSONObject(before)
        // Sending a request must not affect any participant's trading data.
        for (key in listOf("accounts", "positions", "orders", "trades")) {
            assertEquals("Friend request must not alter $key", original.getJSONArray(key).toString(), after.getJSONArray(key).toString())
        }
        val oldRelations = original.getJSONArray("friendships")
        val relations = after.getJSONArray("friendships")
        assertEquals(oldRelations.length() + 1, relations.length())
        val added = (0 until relations.length()).map { relations.getJSONObject(it) }.single { it.getString("id") == sent.id }
        assertEquals("PENDING", added.getString("status"))
        assertEquals(me, added.getString("senderId"))
        assertEquals(participant(7), added.getString("recipientId"))
        for (index in 0 until oldRelations.length()) {
            val previous = oldRelations.getJSONObject(index)
            assertEquals(previous.toString(), (0 until relations.length()).map { relations.getJSONObject(it) }
                .single { it.getString("id") == previous.getString("id") }.toString())
        }
        // Postcondition: reopen the screen; request remains pending exactly once.
        compose.onNodeWithText("Back to Market").performClick()
        openFriends()
        compose.onNodeWithText("Outgoing requests (2)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("E2E Trader 8").performScrollTo().assertIsDisplayed()
        assertEquals(after.toString(), JSONObject(snapshot()).toString())
    }
}
