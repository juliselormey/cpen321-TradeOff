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
import java.io.IOException
import com.google.gson.JsonParseException
import androidx.compose.foundation.lazy.LazyListScope

private val FriendGreen: Color @Composable get() = MaterialTheme.colorScheme.primary
private val FriendMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

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
        } catch (failure: FriendsFailure) {
            error = failure.message
        } catch (_: IOException) {
            error = "Couldn't connect. Check your connection and retry."
        } catch (_: JsonParseException) {
            error = "Could not read the response. Please refresh."
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
                    catch (_: FriendsFailure) { error = "Saved, but the list couldn't refresh. Tap Refresh." }
                    catch (_: IOException) { error = "Saved, but the list couldn't refresh. Tap Refresh." }
                    catch (_: JsonParseException) { error = "Saved, but the response could not be read. Tap Refresh." }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: FriendsFailure) {
                    error = failure.message
                } catch (_: IOException) {
                    error = "Couldn't confirm the change. Refresh before trying again."
                } catch (_: JsonParseException) {
                    error = "Could not read the response. Please refresh."
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
            FriendProfileCard(me, busy,
                onCopy = { me?.let { clipboard.setText(AnnotatedString(it.id)); notice = "Friend ID copied." } },
                onRefresh = { scope.launch { refresh() } },
                onAdd = { error = null; adding = true })
        }
        if (busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = FriendGreen) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        notice?.let { message -> item { Text(message, color = FriendGreen) } }
        incomingRequests(incoming, loaded, busy,
            onAccept = { id -> mutate("Friend request accepted.") { api.accept(it, id) } },
            onDecline = { id -> mutate("Friend request declined.") { api.deleteRequest(it, id) } })
        outgoingRequests(outgoing, loaded, busy,
            onCancel = { id -> mutate("Friend request cancelled.") { api.deleteRequest(it, id) } })
        acceptedFriends(friends, loaded, busy, ascending,
            onSort = { ascending = !ascending }, onRemove = { removing = it })
        item { Text("Updates automatically every 15 seconds while this screen is open.", color = FriendMuted) }
    }

    if (adding) {
        AddFriendDialog(target, busy, error, onChange = { target = it }, onDismiss = { adding = false }) {
            val id = target.trim()
            if (id.equals(me?.id, ignoreCase = true)) error = "You cannot add yourself."
            else mutate("Friend request sent.") { api.send(it, AddFriendRequest(id)) }
        }
    }
    removing?.let { friend ->
        RemoveFriendDialog(friend.user.label, busy, error, onDismiss = { removing = null }) {
            mutate("Friend removed.") { api.remove(it, friend.friendshipId) }
        }
    }
}

@Composable
private fun FriendCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
private fun FriendIdentity(profile: FriendProfile) {
    Text(profile.label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    Text(profile.id, color = FriendMuted, style = MaterialTheme.typography.bodySmall)
}

private fun LazyListScope.incomingRequests(
    incoming: List<FriendRequest>,
    loaded: Boolean,
    busy: Boolean,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit
) {
    item { Text("Incoming requests (${incoming.size})", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
    if (loaded && incoming.isEmpty()) item { Text("No incoming requests.", color = FriendMuted) }
    items(incoming, key = { "in-${it.id}" }) { request ->
        FriendCard {
            FriendIdentity(request.sender)
            Row {
                TextButton(enabled = !busy, onClick = {
                    onAccept(request.id)
                }) { Text("Accept") }
                TextButton(enabled = !busy, onClick = {
                    onDecline(request.id)
                }) { Text("Decline") }
            }
        }
    }

}

@Composable
private fun AddFriendDialog(
    target: String, busy: Boolean, error: String?,
    onChange: (String) -> Unit, onDismiss: () -> Unit, onSend: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Add friend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ask your friend to copy their ID from this screen.")
                OutlinedTextField(value = target, onValueChange = onChange, enabled = !busy,
                    label = { Text("Friend ID") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && target.isNotBlank(), onClick = onSend) {
                Text(if (busy) "Sending…" else "Send request")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RemoveFriendDialog(
    name: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onRemove: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Remove $name?") },
        text = {
            Column {
                Text("You can send a new friend request later.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onRemove) { Text("Remove") } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FriendProfileCard(
    me: FriendProfile?, busy: Boolean,
    onCopy: () -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit
) {

    FriendCard {
        Text("Your friend ID", color = FriendGreen, fontWeight = FontWeight.Bold)
        Text(me?.id ?: "Loading your profile…", color = MaterialTheme.colorScheme.onSurface)
        Text("Share this ID so someone can send you a request.", color = FriendMuted)
        Row {
            TextButton(enabled = me != null, onClick = {
                onCopy()
            }) { Text("Copy ID") }
            TextButton(enabled = !busy, onClick = { onRefresh() }) { Text("Refresh") }
            TextButton(enabled = !busy && me != null, onClick = { onAdd() }) { Text("Add friend") }
        }
    }
}

private fun LazyListScope.outgoingRequests(
    outgoing: List<FriendRequest>, loaded: Boolean, busy: Boolean, onCancel: (String) -> Unit
) {
    item { Text("Outgoing requests (${outgoing.size})", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
    if (loaded && outgoing.isEmpty()) item { Text("No outgoing requests.", color = FriendMuted) }
    items(outgoing, key = { "out-${it.id}" }) { request ->
        FriendCard {
            FriendIdentity(request.recipient)
            TextButton(enabled = !busy, onClick = {
                onCancel(request.id)
            }) { Text("Cancel request") }
        }
    }

}

private fun LazyListScope.acceptedFriends(
    friends: List<Friendship>, loaded: Boolean, busy: Boolean, ascending: Boolean,
    onSort: () -> Unit, onRemove: (Friendship) -> Unit
) {
    item {
        Text("Friends (${friends.size})", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        TextButton(onClick = onSort) { Text(if (ascending) "Name A–Z" else "Name Z–A") }
    }
    if (loaded && friends.isEmpty()) item { Text("No friends yet. Send a request to get started.", color = FriendMuted) }
    val sorted = friends.sortedBy { it.user.label.lowercase(java.util.Locale.ROOT) }
    items(if (ascending) sorted else sorted.reversed(), key = { "friend-${it.friendshipId}" }) { friend ->
        FriendCard {
            FriendIdentity(friend.user)
            TextButton(enabled = !busy, onClick = { onRemove(friend) }) { Text("Remove friend") }
        }
    }
}
