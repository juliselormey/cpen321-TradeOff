package com.example.cpen321application.ui.trade

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.cpen321application.ApiService
import com.example.cpen321application.ui.market.data.MarketApiService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Component regression using real Retrofit proxy objects; no network requests or account mutations. */
class TradeStateRememberTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun recompositionPreservesLoadedCashSharesAndOrderInputsWithRetrofitServices() {
        val retrofit = Retrofit.Builder().baseUrl("http://localhost/")
            .addConverterFactory(GsonConverterFactory.create()).build()
        val api = retrofit.create(ApiService::class.java)
        val market = retrofit.create(MarketApiService::class.java)
        // This is the original reset trigger: passing the proxy itself as a remember key.
        assertFalse(market.equals(market))
        val generation = mutableIntStateOf(0)
        var captured: TradeState? = null
        compose.setContent {
            val state = rememberTradeState("AAPL", api, market, rememberCoroutineScope()) { true }
            Text("Frame ${generation.intValue}")
            SideEffect { captured = state }
        }
        lateinit var original: TradeState
        compose.runOnIdle {
            original = requireNotNull(captured)
            assertFalse(original.accountLoaded)
            assertEquals(0.0, original.availableCash, 0.0)
            original.availableCash = 9289.72
            original.ownedShares = 2.0
            original.accountLoaded = true
            original.updateQuantity("1")
            generation.intValue++
        }
        compose.runOnIdle {
            assertSame(original, captured)
            assertEquals(9289.72, captured!!.availableCash, 0.001)
            assertEquals(2.0, captured!!.ownedShares, 0.0)
            assertEquals("1", captured!!.quantityText)
            assertTrue(captured!!.accountLoaded)
            generation.intValue++
        }
        compose.runOnIdle { assertSame(original, captured) }
    }
}
