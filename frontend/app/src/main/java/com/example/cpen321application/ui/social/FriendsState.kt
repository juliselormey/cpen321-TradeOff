package com.example.cpen321application.ui.social

import androidx.compose.runtime.*
import com.google.gson.JsonParseException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

internal fun friendsError(code: Int): String = when (code) {
    400 -> "Enter a valid friend's username. You cannot add yourself."
    401 -> "Your session expired. Sign out and sign in again from the account tab."
    403 -> "Your account cannot use friends right now."
    404 -> "This username or request was not found. Refresh and try again."
    409 -> "You're already friends or have a pending request. Check incoming requests."
    else -> "The server couldn't complete this request. Please try again."
}

private class FriendsFailure(message: String) : IOException(message)

private fun <T> Response<T>.checked(): Response<T> {
    if (!isSuccessful) throw FriendsFailure(friendsError(code()))
    return this
}


internal class FriendsState(private val api: FriendsApi, private val token: () -> String?) {
    private val lock = Mutex()
    var me by mutableStateOf<FriendProfile?>(null)
        private set
    var friends by mutableStateOf(emptyList<Friendship>())
        private set
    var incoming by mutableStateOf(emptyList<FriendRequest>())
        private set
    var outgoing by mutableStateOf(emptyList<FriendRequest>())
        private set
    var loaded by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var live by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)

    private fun authorization(): String = token()?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
        ?: throw FriendsFailure(friendsError(401))

    private suspend fun load() {
        val auth = authorization()
        val profile = api.me(auth).checked().body() ?: throw FriendsFailure("Unable to load your profile.")
        val list = api.friends(auth).checked().body() ?: throw FriendsFailure("Unable to load friends.")
        val requests = api.requests(auth).checked().body() ?: throw FriendsFailure("Unable to load requests.")
        me = profile.user
        friends = list.friends
        incoming = requests.incoming
        outgoing = requests.outgoing
        loaded = true
    }

    suspend fun refresh() = lock.withLock {
        busy = true
        error = null
        try { request("Couldn't connect. Check your connection and retry.") { load() } }
        finally { busy = false }
    }

    suspend fun send(target: String): Boolean {
        val username = target.trim()
        if (username.equals(me?.username, ignoreCase = true)) { error = "You cannot add yourself."; return false }
        return mutate("Friend request sent.") { api.send(it, AddFriendRequest(username)) }
    }
    suspend fun accept(id: String) = mutate("Friend request accepted.") { api.accept(it, id) }
    suspend fun decline(id: String) = mutate("Friend request declined.") { api.deleteRequest(it, id) }
    suspend fun cancel(id: String) = mutate("Friend request cancelled.") { api.deleteRequest(it, id) }
    suspend fun remove(id: String) = mutate("Friend removed.") { api.remove(it, id) }

    private suspend fun mutate(message: String, action: suspend (String) -> Response<*>): Boolean {
        if (busy) return false
        busy = true
        return try {
            lock.withLock {
                error = null
                notice = null
                val saved = request("Couldn't confirm the change. Refresh before trying again.") { action(authorization()).checked() }
                if (saved) {
                    notice = message
                    if (!request("Saved, but the list couldn't refresh. Tap Refresh.") { load() }) {
                        error = "Saved, but the list couldn't refresh. Tap Refresh."
                    }
                }
                saved
            }
        } finally { busy = false }
    }

    private suspend fun request(fallback: String, call: suspend () -> Unit): Boolean = try {
        call(); true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: FriendsFailure) {
        error = failure.message; false
    } catch (_: IOException) {
        error = fallback; false
    } catch (_: JsonParseException) {
        error = "Could not read the response. Please refresh."; false
    }
}
