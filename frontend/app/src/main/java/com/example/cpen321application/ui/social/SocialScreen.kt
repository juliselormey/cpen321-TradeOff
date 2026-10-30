package com.example.cpen321application.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.cpen321application.ApiClient
import com.example.cpen321application.util.SessionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

private val Background = Color(0xFF090D10)
private val Panel = Color(0xFF141A1E)
private val Green = Color(0xFF87CB70)
private val Muted = Color(0xFFA4ADB3)

internal fun rankFriends(entries: List<LeaderboardEntry>, byReturn: Boolean): List<LeaderboardEntry> =
    entries.sortedWith(compareByDescending<LeaderboardEntry> {
        if (byReturn) it.returnPercent?.let(BigDecimal::valueOf) else it.cashBalance?.toBigDecimalOrNull()
    }.thenBy { it.user.id })

@Composable
fun SocialScreen(
    onBack: () -> Unit,
    onSignOut: () -> Unit = {}
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var sortByReturn by rememberSaveable { mutableStateOf(false) }
    var showAddFriend by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var friendsAscending by rememberSaveable { mutableStateOf(true) }

    val isLeaderboard = selectedTab == 0
    val traders = if (isLeaderboard) leaderboardTraders(sortByReturn) else friendsTraders(friendsAscending)
    val visibleTraders = if (isLeaderboard && !showAll) traders.take(5) else traders
    val sortLabel = when {
        isLeaderboard -> "Sort: ${if (sortByReturn) "Return %" else "Total value"}"
        else -> "Sort: ${if (friendsAscending) "Name A-Z" else "Name Z-A"}"
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Background).padding(horizontal = 20.dp),
    ) {
        SocialTopBar(onBack = onBack, onSignOut = onSignOut)
        SocialTitle()
        TabSelector(selectedTab = selectedTab, onSelect = { selectedTab = it })
        Spacer(Modifier.height(20.dp))
        TraderPanel(
            state = PanelState(isLeaderboard, visibleTraders, showAll, sortLabel),
            actions = PanelActions(
                onToggleShowAll = { showAll = !showAll },
                onToggleSort = {
                    if (isLeaderboard) sortByReturn = !sortByReturn
                    else friendsAscending = !friendsAscending
                },
                onAddFriend = { showAddFriend = true },
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            "Demo portfolios - amounts in USD",
            color = Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }

    if (showAddFriend) {
        AddFriendDialog(onDismiss = { showAddFriend = false })
    }
}

@Composable
private fun SocialTopBar(
    onBack: () -> Unit,
    onSignOut: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) { Text("Back to Market", color = Green) }
        TextButton(onClick = onSignOut) { Text("Sign Out", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SocialTitle() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Social", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TabSelector(selectedTab: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("Leaderboard", "Friends").forEachIndexed { index, title ->
            val selected = selectedTab == index
            Button(
                onClick = { onSelect(index) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected) Green else Panel,
                    contentColor = if (selected) Background else Muted,
                ),
            ) { Text(title) }
        }
    }
}

@Composable
private fun TraderPanel(
    state: PanelState,
    actions: PanelActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth()
            .background(Panel, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        PanelHeader(state, actions)
        TextButton(onClick = actions.onToggleSort) { Text(state.sortLabel, color = Green) }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(state.traders, key = { _, trader -> trader.id }) { index, trader ->
                TraderRow(trader, if (state.isLeaderboard) index + 1 else null)
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            }
        }
        Spacer(Modifier.height(16.dp))
        if (selectedTab == 1) FriendsPanel(Modifier.weight(1f))
        else FriendsLeaderboard(Modifier.weight(1f))
    }
}

@Composable
internal fun FriendsLeaderboard(modifier: Modifier, suppliedLoader: LeaderboardLoader? = null) {
    val context = LocalContext.current.applicationContext

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val defaultLoader = remember(context) { LeaderboardLoader(ApiClient.createFriends(context), { SessionManager.getToken(context) }) }
    val loader = suppliedLoader ?: defaultLoader
    val state by loader.state.collectAsState()
    val entries = state.entries
    val busy = state.loading
    val loaded = state.loaded
    val error = state.error



    var byReturn by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(loader, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            loader.refresh()
            while (isActive) {
                delay(15_000)
                // Failed requests wait for an explicit retry; avoid an endless
                // spinner/retry cycle when the session or connection is broken.
                if (loader.state.value.error == null) loader.refresh()
            }
        }
    }
    val ranked = rankFriends(entries, byReturn)
    Column(modifier.fillMaxWidth()) {
        Text("Friends leaderboard", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("You and your accepted friends • USD cash balances", color = Muted)
        Text("Cash change is measured against starting cash; stock holdings are not included.",
            color = Muted, style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { byReturn = !byReturn }) { Text(if (byReturn) "Cash change %" else "Cash balance", color = Green) }
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Top 5" else "See all", color = Green) }
            TextButton(enabled = !busy, onClick = { scope.launch { loader.refresh() } }) { Text("Refresh", color = Green) }
        }
        if (busy && !loaded) {
            Text("Loading leaderboard…", color = Muted)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Green)
        }
        error?.let { Text(it, color = Color(0xFFFFB4AB)) }
        if (loaded && entries.none { !it.isYou }) Text("Accept a friend request to compare with friends.", color = Muted)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(if (showAll) ranked else ranked.take(5), key = { _, entry -> entry.user.id }) { index, entry ->
                Row(Modifier.fillMaxWidth().background(if (entry.isYou) Green.copy(alpha = 0.12f) else Panel,
                    RoundedCornerShape(12.dp)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (entry.cashBalance == null && !byReturn || byReturn && entry.returnPercent == null) "—" else "${index + 1}", color = Green)
                    Column(Modifier.weight(1f)) {
                        Text(entry.user.label + if (entry.isYou) " (You)" else "", color = Color.White,
                            fontWeight = FontWeight.SemiBold)
                        Text(entry.returnPercent?.let { String.format(Locale.US, "%+.1f%% cash change", it) }
                            ?: "Cash change unavailable", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(entry.cashBalance?.toBigDecimalOrNull()?.let {
                        NumberFormat.getCurrencyInstance(Locale.US).format(it)
                    } ?: "Unavailable", color = Color.White)
                }
            }
        }
    }
}
