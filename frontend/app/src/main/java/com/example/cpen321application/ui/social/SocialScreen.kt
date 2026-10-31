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

private val Background: Color @Composable get() = MaterialTheme.colorScheme.background
private val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
private val Green: Color @Composable get() = MaterialTheme.colorScheme.primary
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

internal fun rankFriends(entries: List<LeaderboardEntry>, byReturn: Boolean): List<LeaderboardEntry> =
    entries.sortedWith(compareByDescending<LeaderboardEntry> {
        if (byReturn) it.returnPercent?.let(BigDecimal::valueOf) else it.cashBalance?.toBigDecimalOrNull()
    }.thenBy { it.user.id })

@Composable
fun SocialScreen(onBack: () -> Unit) {
    var selectedTab by rememberSaveable { mutableIntStateOf(1) }
    MaterialTheme {
        Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 20.dp)) {
            TextButton(onClick = onBack) { Text("Back to home", color = Green) }
            Text("Social", color = MaterialTheme.colorScheme.onSurface, fontSize = 32.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 20.dp))
            Row(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(4.dp)) {
                listOf("Leaderboard", "Friends").forEachIndexed { index, label ->
                    Button(onClick = { selectedTab = index }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedTab == index) Green else Panel,
                            contentColor = if (selectedTab == index) MaterialTheme.colorScheme.onPrimary else Muted)) { Text(label) }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (selectedTab == 1) FriendsPanel(Modifier.weight(1f))
            else FriendsLeaderboard(Modifier.weight(1f))
        }
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
        Text("Friends leaderboard", color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
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
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loaded && entries.none { !it.isYou }) Text("Accept a friend request to compare with friends.", color = Muted)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(if (showAll) ranked else ranked.take(5), key = { _, entry -> entry.user.id }) { index, entry ->
                Row(Modifier.fillMaxWidth().background(if (entry.isYou) Green.copy(alpha = 0.12f) else Panel,
                    RoundedCornerShape(12.dp)).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val hasRankingValue = if (byReturn) entry.returnPercent != null else entry.cashBalance != null
                    Text(if (!hasRankingValue) "—" else "${index + 1}", color = Green)
                    Column(Modifier.weight(1f)) {
                        Text(entry.user.label + if (entry.isYou) " (You)" else "", color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold)
                        Text(entry.returnPercent?.let { String.format(Locale.US, "%+.1f%% cash change", it) }
                            ?: "Cash change unavailable", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(entry.cashBalance?.toBigDecimalOrNull()?.let {
                        NumberFormat.getCurrencyInstance(Locale.US).format(it)
                    } ?: "Unavailable", color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
