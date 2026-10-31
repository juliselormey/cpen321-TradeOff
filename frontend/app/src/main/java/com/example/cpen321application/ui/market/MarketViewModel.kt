package com.example.cpen321application.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.cpen321application.ui.market.data.MarketRepository
import com.example.cpen321application.ui.market.data.MarketStock
import com.example.cpen321application.ui.market.data.initialMarketStocks
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

data class MarketUiState(
    val stocks: List<MarketStock> = initialMarketStocks(),
    val refreshing: Boolean = false,
    val checkedAt: String? = null,
    val error: String? = null,
)

class MarketViewModel(private val repository: MarketRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(MarketUiState())
    val state = mutableState.asStateFlow()
    private val refreshLock = Mutex()

    suspend fun refresh() = refreshLock.withLock {
        mutableState.value = mutableState.value.copy(refreshing = true)
        try {
            val stocks = repository.loadStocks()
            mutableState.value = MarketUiState(stocks = stocks, checkedAt = Instant.now().toString())
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            showFailure("Cannot reach the backend. Check the server and your connection.")
        } catch (error: HttpException) {
            showFailure("Market endpoint returned HTTP " + error.code() + ". Check the backend version.")
        } catch (_: com.google.gson.JsonParseException) {
            showFailure("Could not read market data. Try again shortly.")
        } finally {
            mutableState.value = mutableState.value.copy(refreshing = false)
        }
    }

    private fun showFailure(message: String) {
        mutableState.value = mutableState.value.copy(
            stocks = mutableState.value.stocks.map {
                if (it.source == "alpaca" && it.currentPrice != null) {
                    it.copy(status = "stale", history = it.history?.copy(status = "stale"))
                } else it
            },
            error = message
        )
    }

    class Factory(private val repository: MarketRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(MarketViewModel::class.java))
            return modelClass.cast(MarketViewModel(repository)) ?: error("Cannot create MarketViewModel")
        }
    }
}
