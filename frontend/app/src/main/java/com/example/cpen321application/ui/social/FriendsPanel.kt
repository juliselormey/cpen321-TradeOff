package com.example.cpen321application.ui.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.cpen321application.ApiClient
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.launch
import okhttp3.WebSocket

@Composable
fun FriendsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val state = remember(context) { FriendsState(ApiClient.createFriends(context)) { SessionManager.getToken(context) } }
    val events = remember(context) { ApiClient.createFriendEventsClient(context) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var ascending by rememberSaveable { mutableStateOf(true) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf("") }
    var removing by remember { mutableStateOf<Friendship?>(null) }
    FriendsLiveUpdates(state, events)

    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            FriendProfileCard(state.me, state.busy,
                onCopy = { state.me?.username?.let { username ->
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Username", username)))
                        state.notice = "Username copied."
                    }
                } },
                onRefresh = { scope.launch { state.refresh() } }, onAdd = { state.error = null; adding = true })
        }
        if (state.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = FriendGreen) }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        state.notice?.let { item { Text(it, color = FriendGreen) } }
        incomingRequests(state.incoming, state.loaded, state.busy,
            onAccept = { id -> scope.launch { state.accept(id) } }, onDecline = { id -> scope.launch { state.decline(id) } })
        outgoingRequests(state.outgoing, state.loaded, state.busy, onCancel = { id -> scope.launch { state.cancel(id) } })
        acceptedFriends(state.friends, state.loaded, state.busy, ascending,
            onSort = { ascending = !ascending }, onRemove = { removing = it })
        item { Text(if (state.live) "Live updates connected." else "Live updates disconnected. Reconnecting when possible; you can tap Refresh.", color = FriendMuted) }
    }
    if (adding) {
        AddFriendDialog(target, state.busy, state.error, onChange = { target = it }, onDismiss = { adding = false }) {
            scope.launch { if (state.send(target)) { adding = false; target = "" } }
        }
    }
    removing?.let { friend ->
        RemoveFriendDialog(friend.user.label, state.busy, state.error, onDismiss = { removing = null }) {
            scope.launch { if (state.remove(friend.friendshipId)) removing = null }
        }
    }
}

@Composable
private fun FriendsLiveUpdates(state: FriendsState, client: WebSocket.Factory) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, client, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            state.refresh()
            try {
                friendUpdates(client, ApiClient.friendEventsUrl()).collect { update ->
                    when (update) {
                        FriendUpdate.REFRESH -> { state.live = true; state.refresh() }
                        FriendUpdate.RECONNECTING -> state.live = false
                        FriendUpdate.UNAUTHORIZED -> { state.live = false; state.error = friendsError(401) }
                    }
                }
            } finally { state.live = false }
        }
    }
}
