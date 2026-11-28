package com.example.cpen321application.ui.market.data

import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

/** Revalue private data on public cache events; never broadcast account data. */
internal suspend fun observeMarketRefreshes(
    stream: MarketStream,
    onSnapshot: () -> Unit,
    onDisconnect: () -> Unit,
    refresh: suspend () -> Unit,
) {
    var retryDelay = 1_000L
    stream.snapshots().map<MarketSnapshot, MarketSnapshot?> { it }
        .retryWhen { cause, _ ->
            if (cause !is IOException) return@retryWhen false
            emit(null)
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            true
        }.conflate().collectLatest { snapshot ->
            if (snapshot == null) onDisconnect()
            else {
                retryDelay = 1_000L
                onSnapshot()
                // Cancellation prevents an older valuation from replacing a newer event.
                refresh()
            }
        }
}
