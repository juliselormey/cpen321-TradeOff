package com.example.cpen321application.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyListScope

internal val FriendGreen: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val FriendMuted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@Composable
internal fun FriendCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
internal fun FriendIdentity(profile: FriendProfile) {
    Text(profile.label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    Text(profile.username ?: "Username not set", color = FriendMuted, style = MaterialTheme.typography.bodySmall)
}

internal fun LazyListScope.incomingRequests(
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
internal fun FriendProfileCard(
    me: FriendProfile?, busy: Boolean,
    onCopy: () -> Unit, onRefresh: () -> Unit, onAdd: () -> Unit
) {

    FriendCard {
        Text("Your username (friend ID)", color = FriendGreen, fontWeight = FontWeight.Bold)
        Text(if (me == null) "Loading your profile…" else me.username ?: "Set a username in Settings", color = MaterialTheme.colorScheme.onSurface)
        Text("Share your username so someone can send you a request.", color = FriendMuted)
        Row {
            TextButton(enabled = !me?.username.isNullOrBlank(), onClick = {
                onCopy()
            }) { Text("Copy username") }
            TextButton(enabled = !busy, onClick = { onRefresh() }) { Text("Refresh") }
            TextButton(enabled = !busy && me != null, onClick = { onAdd() }) { Text("Add friend") }
        }
    }
}

internal fun LazyListScope.outgoingRequests(
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

internal fun LazyListScope.acceptedFriends(
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
