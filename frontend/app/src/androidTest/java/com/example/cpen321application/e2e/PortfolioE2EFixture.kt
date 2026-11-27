package com.example.cpen321application.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.closeSoftKeyboard
import com.example.cpen321application.ApiClient
import com.example.cpen321application.BuildConfig
import com.example.cpen321application.MainActivity
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** View Portfolio, formal specification 3.5.3. Real UI, backend, SQL and Alpaca. */
abstract class PortfolioE2EFixture {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    protected val api get() = ApiClient.create(compose.activity)
    protected val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    protected var prepared = false
    protected val displayFailures = mutableListOf<String>()

    @Before
    fun prepareIndependentAccount() {

        val url = BuildConfig.API_BASE_URL.trimEnd('/')
        check(url.isNotBlank()) {
            "Configure API_BASE_URL for the E2E-enabled backend."
        }
        check(BuildConfig.MARKET_API_BASE_URL.trimEnd('/') == url) {
            "Portfolio and market data must use the same real backend."
        }
        // Preconditions: the tester has already signed in through Google.
        check(SessionManager.isLoggedIn(compose.activity)) { "Sign in and return to Market before running." }
        resetAccount()
        prepared = true
        compose.activityRule.scenario.recreate()
        waitFor("Market")
    }

    @After
    fun cleanAccount() {
        // Restore the disposable account even when an assertion fails.
        if (prepared) resetAccount()
    }

    protected fun authorizedRequest(path: String) = Request.Builder()
        .url(BuildConfig.API_BASE_URL.trimEnd('/') + path)
        .header("Authorization", "Bearer " + requireNotNull(SessionManager.getToken(compose.activity)))

    protected fun resetAccount() {
        val request = authorizedRequest("/__e2e/account/reset").post("".toRequestBody()).build()
        client.newCall(request).execute().use {
            check(it.isSuccessful) { "Portfolio setup HTTP ${it.code}; verify the local server and Google session." }
        }
    }

    protected fun portfolioEvidence(): JSONObject {
        client.newCall(authorizedRequest("/api/portfolio").get().build()).execute().use {
            check(it.isSuccessful) { "Portfolio HTTP ${it.code}; a real price is required for held stocks." }
            return JSONObject(requireNotNull(it.body).string())
        }
    }

    protected fun accountSnapshot() = runBlocking {
        val response = api.getPortfolio()
        check(response.isSuccessful) { "Portfolio HTTP ${response.code()}" }
        requireNotNull(response.body())
    }

    protected fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun tab(name: String) {
        compose.onNode(hasText(name) and hasClickAction()).performClick()
    }

    protected fun scrollTo(tag: String) {
        compose.onNodeWithTag("portfolio-list").performScrollToNode(hasTestTag(tag))
    }

    protected fun waitForCash(cash: Double) {
        scrollTo("portfolio-cash")
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasTestTag("portfolio-cash") and hasText(money(cash)))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    protected fun money(value: Double): String = NumberFormat.getCurrencyInstance(Locale.US).format(value)

    protected fun percent(value: Double): String = String.format(Locale.US, "%.1f%%", value)

    protected fun texts(tag: String): List<String> = compose.onNodeWithTag(tag).fetchSemanticsNode()
        .config[SemanticsProperties.Text].map { it.text }

    protected fun verifyDisplay(step: String, assertion: () -> Unit) {
        // Collect display discrepancies so later use-case steps and read-only checks still execute.
        try {
            assertion()
        } catch (error: AssertionError) {
            displayFailures.add("$step: ${error.message}")
        }
    }

    protected fun submitSetupBuy() {
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText("BUY") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("BUY").performScrollTo().assertIsEnabled().performClick()
    }

    protected fun prepareHoldingAndPendingOrder() {
        // Small data setup uses the UI, as required by the project description.
        tab("Market")
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("MSFT")
        closeSoftKeyboard()
        waitFor("Microsoft Corporation")
        compose.onNode(hasText("Microsoft Corporation") and hasClickAction()).performScrollTo().performClick()
        waitFor("Trade MSFT")
        compose.onNodeWithText("Market Order").performScrollTo().performClick()
        compose.onNodeWithText("Quantity (Shares)").performScrollTo().performTextReplacement("2")
        closeSoftKeyboard()
        submitSetupBuy()
        waitFor("MARKET BUY filled", substring = true)

        // A real price above $1 ensures the $1 limit BUY remains pending and reserves $100.
        val price = runBlocking {
            ApiClient.createMarket(compose.activity).getStocks().stocks.first { it.ticker == "MSFT" }
        }
        check(requireNotNull(price.currentPrice).toDouble() > 1.0) { "MSFT must exceed the test limit." }
        compose.onNodeWithText("Limit Order").performScrollTo().performClick()
        compose.onNodeWithText("Quantity (Shares)").performScrollTo().performTextReplacement("100")
        compose.onNodeWithText("Limit Price ($)").performScrollTo().performTextReplacement("1")
        closeSoftKeyboard()
        submitSetupBuy()
        waitFor("LIMIT BUY placed", substring = true)
        compose.onNodeWithContentDescription("Back to Market").performScrollTo().performClick()
        waitFor("Market")
    }

}
