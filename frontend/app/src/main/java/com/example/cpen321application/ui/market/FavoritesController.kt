package com.example.cpen321application.ui.market

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.cpen321application.ApiService
import java.io.IOException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import retrofit2.HttpException

internal data class FavoritesUi(
    val tickers: Set<String>,
    val signedIn: Boolean,
    val busy: Boolean,
    val error: String?,
)

@Stable
internal class FavoritesController(
    private val api: ApiService,
    private val sessionToken: String?,
    private val currentToken: () -> String?,
    private val scope: CoroutineScope,
) {
    var tickers by mutableStateOf<Set<String>?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var needsSignIn by mutableStateOf(false)
        private set

    val signedIn: Boolean get() = !sessionToken.isNullOrEmpty()

    fun load() = request { api.getStockFavorites(it).tickers.toSet() }

    fun change(ticker: String, favorite: Boolean, onSignInRequired: () -> Unit) {
        val current = tickers
        when {
            !signedIn || needsSignIn -> onSignInRequired()
            current == null -> load()
            else -> request { authorization ->
                if (favorite) {
                    api.addStockFavorite(authorization, ticker)
                    current + ticker
                } else {
                    api.removeStockFavorite(authorization, ticker)
                    current - ticker
                }
            }
        }
    }

    private fun request(call: suspend (String) -> Set<String>) {
        if (sessionToken.isNullOrEmpty() || busy || currentToken() != sessionToken) return
        busy = true
        error = null
        needsSignIn = false
        scope.launch {
            try {
                tickers = call("Bearer $sessionToken")
            } catch (e: HttpException) {
                needsSignIn = e.code() == 401
                error = if (needsSignIn) "Sign in again to access your favorites."
                else "Favorites request failed (HTTP ${e.code()})."
            } catch (_: IOException) {
                error = "Could not reach the favorites server."
            } catch (_: com.google.gson.JsonParseException) {
                error = "Could not read your favorites."
            } finally {
                busy = false
            }
        }
    }
}
