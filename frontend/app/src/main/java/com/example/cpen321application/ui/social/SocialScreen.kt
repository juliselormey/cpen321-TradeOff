package com.example.cpen321application.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 20.dp),
    ) {
        SocialTopBar(onBack = onBack, onSignOut = onSignOut)
        SocialTitle()
        TabSelector(selectedTab = selectedTab, onSelect = { selectedTab = it })
        Spacer(Modifier.height(20.dp))
        if (selectedTab == 0) {
            FriendsLeaderboard(Modifier.weight(1f))
        } else {
            FriendsPanel(Modifier.weight(1f))
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Social", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TabSelector(selectedTab: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(16.dp))
            .padding(4.dp),
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
internal fun FriendsLeaderboard(modifier: Modifier = Modifier, suppliedLoader: LeaderboardLoader? = null) {
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
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { byReturn = !byReturn }) { Text(if (byReturn) "Cash change %" else "Cash balance", color = Green) }
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Top 5" else "See all", color = Green) }
            TextButton(enabled = !busy, onClick = { scope.launch { loader.refresh() } }) { Text("Refresh", color = Green) }
        }
        if (busy && !loaded) {
            Spacer(Modifier.height(8.dp))
            Text("Loading leaderboard…", color = Muted)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Green)
        }
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Color(0xFFFFB4AB))
        }
        if (loaded && entries.none { !it.isYou }) {
            Spacer(Modifier.height(8.dp))
            Text("Accept a friend request to compare with friends.", color = Muted)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(if (showAll) ranked else ranked.take(5), key = { _, entry -> entry.user.id }) { index, entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (entry.isYou) Green.copy(alpha = 0.12f) else Panel, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(if (entry.cashBalance == null && !byReturn || byReturn && entry.returnPercent == null) "—" else "${index + 1}", color = Green, fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text(entry.user.label + if (entry.isYou) " (You)" else "", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Text(entry.returnPercent?.let { String.format(Locale.US, "%+.1f%% cash change", it) }
                            ?: "Cash change unavailable", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(entry.cashBalance?.toBigDecimalOrNull()?.let {
                        NumberFormat.getCurrencyInstance(Locale.US).format(it)
                    } ?: "Unavailable", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
