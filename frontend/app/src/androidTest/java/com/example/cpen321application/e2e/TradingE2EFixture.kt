package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.ApiClient
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import com.example.cpen321application.PortfolioSummaryResponse
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Place Market Buy, formal specification 3.5.2. Uses real HTTP, SQL and Alpaca. */
abstract class TradingE2EFixture {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    protected val api get() = ApiClient.create(compose.activity)
    protected var prepared = false

    @Before
    fun prepareIndependentAccount() {
        // Test isolation: use the disposable backend for both quotes and orders.
        val backendUrl = BuildConfig.API_BASE_URL.trimEnd('/')
        check(backendUrl.isNotBlank()) {
            "Configure API_BASE_URL for the E2E-enabled backend."
        }
        check(BuildConfig.MARKET_API_BASE_URL.trimEnd('/') == backendUrl) {
            "Market and trading must use the same real E2E backend."
        }
        // Use-case precondition: a Trader is already signed in through Google.
        check(SessionManager.isLoggedIn(compose.activity)) {
            "Sign in with your testing Google account before running; start on Market."
        }
        // Course independence requirement: create the starting account state automatically.
        resetAccount()
        prepared = true
        compose.activityRule.scenario.recreate()
        waitFor("Market")
    }

    @After
    fun cleanAccount() {
        // Remove this test's trades and holdings even if a test assertion fails.
        if (prepared) resetAccount()
    }

    protected fun resetAccount() {
        val token = requireNotNull(SessionManager.getToken(compose.activity))
        val request = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/__e2e/account/reset")
            .header("Authorization", "Bearer $token")
            .post("".toRequestBody())
            .build()
        ApiClient.createBackendHttpClient(compose.activity)
            .newCall(request).execute().use {
                check(it.isSuccessful) { "Account setup failed (HTTP ${it.code}); check server and sign-in." }
            }
    }

    protected fun snapshot(): PortfolioSummaryResponse = runBlocking {
        val response = api.getPortfolio()
        check(response.isSuccessful) { "Portfolio HTTP ${response.code()}" }
        requireNotNull(response.body())
    }

    protected fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun quantity(value: String) {
        compose.onNodeWithText("Quantity (Shares)").performScrollTo().performTextReplacement(value)
        closeSoftKeyboard()
    }

    protected fun waitForBuyEnabled() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("BUY") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun openMicrosoft() {
        // Success 2: select MSFT in Market, following the app's actual navigation.
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("MSFT")
        closeSoftKeyboard()
        waitFor("Microsoft Corporation")
        compose.onNode(hasText("Microsoft Corporation") and hasClickAction()).performScrollTo().performClick()
        // Success 1: verify that selecting the stock opened its Trading page.
        waitFor("Trade MSFT")
        // Success 4: choose Market Order; BUY is the submission action below.
        compose.onNodeWithText("Market Order").performScrollTo().performClick()
    }

}
