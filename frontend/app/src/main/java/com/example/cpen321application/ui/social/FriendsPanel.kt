package com.example.cpen321application.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.cpen321application.ApiClient
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

private val FriendGreen = Color(0xFF87CB70)
private val FriendMuted = Color(0xFFA4ADB3)

internal fun friendsError(code: Int): String = when (code) {
    400 -> "Enter a valid friend's user ID. You cannot add yourself."
    401 -> "Your session expired. Sign out and sign in again from the account tab."
    403 -> "Your account cannot use friends right now."
    404 -> "This user or request is no longer available. Refresh and try again."
    409 -> "You're already friends or have a pending request. Check incoming requests."
    else -> "The server couldn't complete this request. Please try again."
}

private class FriendsFailure(message: String) : Exception(message)

private fun <T> Response<T>.checked(): Response<T> {
    if (!isSuccessful) throw FriendsFailure(friendsError(code()))
    return this
}

@Composable
fun FriendsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val api = remember(context) { ApiClient.createFriends(context) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clipboard = LocalClipboardManager.current
    val lock = remember { Mutex() }
    var me by remember { mutableStateOf<FriendProfile?>(null) }
    var friends by remember { mutableStateOf(emptyList<Friendship>()) }
    var incoming by remember { mutableStateOf(emptyList<FriendRequest>()) }
    var outgoing by remember { mutableStateOf(emptyList<FriendRequest>()) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var ascending by rememberSaveable { mutableStateOf(true) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf("") }
    var removing by remember { mutableStateOf<Friendship?>(null) }

    fun authorization(): String {
        val token = SessionManager.getToken(context)
        if (token.isNullOrBlank()) throw FriendsFailure(friendsError(401))
        return "Bearer $token"
    }

    suspend fun load() {
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
        try {
            load()
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = if (failure is FriendsFailure) failure.message
                else "Couldn't connect. Check your connection and retry."
        } finally { busy = false }
    }

    fun mutate(message: String, action: suspend (String) -> Response<*>) {
        if (busy) return
        busy = true
        scope.launch {
            lock.withLock {
                error = null
                notice = null
                try {
                    action(authorization()).checked()
                    adding = false
                    target = ""
                    removing = null
                    notice = message
                    try { load() } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Saved, but the list couldn't refresh. Tap Refresh." }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    error = if (failure is FriendsFailure) failure.message
                        else "Couldn't confirm the change. Refresh before trying again."
                } finally { busy = false }
            }
        }
    }

    LaunchedEffect(context, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                refresh()
                delay(15_000)
            }
        }
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FriendCard {
                Text("Your friend ID", color = FriendGreen, fontWeight = FontWeight.Bold)
                Text(me?.id ?: "Loading your profile…", color = Color.White)
                Text("Share this ID so someone can send you a request.", color = FriendMuted)
                Row {
                    TextButton(enabled = me != null, onClick = {
                        me?.let { clipboard.setText(AnnotatedString(it.id)); notice = "Friend ID copied." }
                    }) { Text("Copy ID") }
                    TextButton(enabled = !busy, onClick = { scope.launch { refresh() } }) { Text("Refresh") }
                    TextButton(enabled = !busy && me != null, onClick = { error = null; adding = true }) { Text("Add friend") }
                }
            }
        }
        if (busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = FriendGreen) }
        error?.let { message -> item { Text(message, color = Color(0xFFFFB4AB)) } }
        notice?.let { message -> item { Text(message, color = FriendGreen) } }
        item { Text("Incoming requests (${incoming.size})", color = Color.White, fontWeight = FontWeight.Bold) }
        if (loaded && incoming.isEmpty()) item { Text("No incoming requests.", color = FriendMuted) }
        items(incoming, key = { "in-${it.id}" }) { request ->
            FriendCard {
                FriendIdentity(request.sender)
                Row {
                    TextButton(enabled = !busy, onClick = {
                        mutate("Friend request accepted.") { api.accept(it, request.id) }
                    }) { Text("Accept") }
                    TextButton(enabled = !busy, onClick = {
                        mutate("Friend request declined.") { api.deleteRequest(it, request.id) }
                    }) { Text("Decline") }
                }
            }
        }
        item { Text("Outgoing requests (${outgoing.size})", color = Color.White, fontWeight = FontWeight.Bold) }
        if (loaded && outgoing.isEmpty()) item { Text("No outgoing requests.", color = FriendMuted) }
        items(outgoing, key = { "out-${it.id}" }) { request ->
            FriendCard {
                FriendIdentity(request.recipient)
                TextButton(enabled = !busy, onClick = {
                    mutate("Friend request cancelled.") { api.deleteRequest(it, request.id) }
                }) { Text("Cancel request") }
            }
        }
        item {
            Text("Friends (${friends.size})", color = Color.White, fontWeight = FontWeight.Bold)
            TextButton(onClick = { ascending = !ascending }) { Text(if (ascending) "Name A–Z" else "Name Z–A") }
        }
        if (loaded && friends.isEmpty()) item { Text("No friends yet. Send a request to get started.", color = FriendMuted) }
        val sorted = friends.sortedBy { it.user.label.lowercase(java.util.Locale.ROOT) }
        items(if (ascending) sorted else sorted.reversed(), key = { "friend-${it.friendshipId}" }) { friend ->
            FriendCard {
                FriendIdentity(friend.user)
                TextButton(enabled = !busy, onClick = { removing = friend }) { Text("Remove friend") }
            }
        }
        item { Text("Updates automatically every 15 seconds while this screen is open.", color = FriendMuted) }
    }

    if (adding) AlertDialog(
        onDismissRequest = { if (!busy) adding = false },
        title = { Text("Add friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ask your friend to copy their ID from this screen.")
                OutlinedTextField(value = target, onValueChange = { target = it }, enabled = !busy,
                    label = { Text("Friend ID") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && target.isNotBlank(), onClick = {
                val id = target.trim()
                if (id.equals(me?.id, ignoreCase = true)) error = "You cannot add yourself."
                else mutate("Friend request sent.") { api.send(it, AddFriendRequest(id)) }
            }) { Text(if (busy) "Sending…" else "Send request") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { adding = false }) { Text("Cancel") } },
    )
    removing?.let { friend ->
        AlertDialog(onDismissRequest = { if (!busy) removing = null },
            title = { Text("Remove ${friend.user.label}?") },
            text = { Column {
                Text("You can send a new friend request later.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                mutate("Friend removed.") { api.remove(it, friend.friendshipId) }
            }) { Text("Remove") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { removing = null }) { Text("Cancel") } })
    }
}

@Composable
private fun FriendCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Color(0xFF141A1E), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun FriendIdentity(profile: FriendProfile) {
    Text(profile.label, color = Color.White, fontWeight = FontWeight.SemiBold)
    Text(profile.id, color = FriendMuted, style = MaterialTheme.typography.bodySmall)
}
