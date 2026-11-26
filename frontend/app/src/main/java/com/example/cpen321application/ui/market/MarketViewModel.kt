package com.example.cpen321application.ui.market

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.cpen321application.ui.market.data.MarketRepository
import com.example.cpen321application.ui.market.data.MarketStock
import com.example.cpen321application.ui.market.data.MarketStream
import com.example.cpen321application.ui.market.data.initialMarketStocks
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var streamGeneration = 0L

    /** Lifecycle owns this collector; cancellation closes the socket and retry loop. */
    suspend fun observeMarket(stream: MarketStream) {
        var retryDelay = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                stream.snapshots().collect { snapshot ->
                    streamGeneration++
                    retryDelay = 1_000L
                    mutableState.value = MarketUiState(stocks = snapshot.stocks, checkedAt = snapshot.checkedAt)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: IOException) {
                // One REST recovery per failed connection, never a second polling loop.
                refresh()
                showFailure("Live market disconnected. Reconnecting…")
            }
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
        }
    }

    suspend fun refresh() = refreshLock.withLock {
        mutableState.value = mutableState.value.copy(refreshing = true)
        val generation = streamGeneration
        try {
            val stocks = repository.loadStocks()
            if (generation == streamGeneration) {
                mutableState.value = MarketUiState(stocks = stocks, checkedAt = Instant.now().toString())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            if (generation == streamGeneration) showFailure("Cannot reach the backend. Check the server and your connection.")
        } catch (error: HttpException) {
            if (generation == streamGeneration) showFailure("Market endpoint returned HTTP " + error.code() + ". Check the backend version.")
        } catch (_: com.google.gson.JsonParseException) {
            if (generation == streamGeneration) showFailure("Could not read market data. Try again shortly.")
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
