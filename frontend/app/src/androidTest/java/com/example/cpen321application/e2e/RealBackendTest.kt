package com.example.cpen321application.e2e

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.*
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Before
import org.junit.Rule
import java.util.Locale

/** Real app, backend and SQL database; only isolated fault controls are injected. */
abstract class RealBackendTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    protected val api get() = ApiClient.create(compose.activity)
    protected val market get() = ApiClient.createMarket(compose.activity)
    private var ready = false

    @Before fun prepareAccount() {
        check(BuildConfig.API_BASE_URL.trimEnd('/') == "http://10.0.2.2:3002") {
            "Portfolio E2E needs the isolated backend URL in local.properties."
        }
        check(SessionManager.isLoggedIn(compose.activity)) { "Sign in before running Portfolio E2E." }
        control("reset", "{}")
        ready = true
        compose.activityRule.scenario.recreate()
        waitText("Alpaca IEX connected · 6 stocks")
    }

    @After fun restoreAccount() { if (ready) control("reset", "{}") }

    protected fun control(action: String, json: String) {
        val token = SessionManager.getToken(compose.activity) ?: error("Sign-in required")
        val request = Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/__e2e/$action")
            .header("Authorization", "Bearer $token")
            .post(json.toRequestBody("application/json".toMediaType())).build()
        OkHttpClient().newCall(request).execute().use {
            check(it.isSuccessful) { "E2E setup failed (HTTP ${it.code}). Check the test database and account email." }
        }
    }

    protected fun fault(mode: String) = control("fault", "{\"mode\":\"$mode\"}")
    protected fun snapshot() = runBlocking { api.getPortfolio().let {
        check(it.isSuccessful) { "Portfolio HTTP ${it.code()}" }; requireNotNull(it.body())
    } }
    protected fun seedHolding(quantity: Double = 2.0) = runBlocking {
        val result = api.executeMarketOrder(ExecuteMarketOrderRequest("MSFT", "BUY", quantity, 0.0))
        check(result.isSuccessful) { "Real setup buy failed: HTTP ${result.code()}" }
    }
    protected fun waitText(text: String, substring: Boolean = false, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }
    }
    protected fun tab(name: String) = compose.onNode(hasText(name) and hasClickAction()).performClick()
    protected fun pollingTick() { compose.mainClock.advanceTimeBy(8_100) }
    protected fun order(side: String) {
        compose.onNodeWithText("Quantity (Shares)").performScrollTo().performTextReplacement("1")
        closeSoftKeyboard()
        compose.onNodeWithText(side).performScrollTo().performClick()
        waitText("MARKET $side filled", substring = true)
    }
    protected fun money(value: Double): String = java.text.NumberFormat.getCurrencyInstance(Locale.US).format(value)
}
