package com.example.cpen321application.ui.social

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

private val Background = Color(0xFF090D10)
private val Panel = Color(0xFF141A1E)
private val Green = Color(0xFF87CB70)
private val Muted = Color(0xFFA4ADB3)

private data class SocialTrader(
    val id: String,
    val name: String,
    val totalValue: Double,
    val returnPercent: Double,
    val isYou: Boolean = false,
)

private val sampleTraders = listOf(
    SocialTrader("alex", "Alex Chen", 124532.0, 24.8),
    SocialTrader("daniel", "Daniel Kim", 118205.0, 18.1),
    SocialTrader("sophia", "Sophia Lee", 112408.0, 16.3),
    SocialTrader("you", "You", 106234.0, 12.7, isYou = true),
    SocialTrader("ethan", "Ethan Park", 104872.0, 11.9),
    SocialTrader("maya", "Maya Singh", 98421.0, 9.4),
    SocialTrader("ryan", "Ryan Tran", 91305.0, -2.1),
)

@Composable
fun SocialScreen(onBack: () -> Unit) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var sortByReturn by rememberSaveable { mutableStateOf(false) }
    var showAddFriend by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var friendsAscending by rememberSaveable { mutableStateOf(true) }
    val traders = if (selectedTab == 0) {
        if (sortByReturn) sampleTraders.sortedByDescending { it.returnPercent }
        else sampleTraders.sortedByDescending { it.totalValue }
    } else {
        sampleTraders.filterNot { it.isYou }.let { friends ->
            if (friendsAscending) friends.sortedBy { it.name }
            else friends.sortedByDescending { it.name }
        }
    }
    val visibleTraders = if (selectedTab == 0 && !showAll) traders.take(5) else traders

    Column(
        modifier = Modifier.fillMaxSize().background(Background).padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back to home", color = Green) }
            Text("Sample data", color = Muted, fontSize = 12.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Social", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        }
        Row(
            modifier = Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("Leaderboard", "Friends").forEachIndexed { index, title ->
                Button(
                    onClick = { selectedTab = index },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedTab == index) Green else Panel,
                        contentColor = if (selectedTab == index) Background else Muted,
                    ),
                ) { Text(title) }
            }
        }
        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .background(Panel, RoundedCornerShape(20.dp)).padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (selectedTab == 0) "Leaderboard" else "Friends (${traders.size})",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selectedTab == 0) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show top 5" else "See all", color = Green)
                    }
                } else {
                    TextButton(onClick = { showAddFriend = true }) {
                        Text("+ Add friend", color = Green)
                    }
                }
            }
            if (selectedTab == 0) {
                TextButton(onClick = { sortByReturn = !sortByReturn }) {
                    Text("Sort: ${if (sortByReturn) "Return %" else "Total value"}", color = Green)
                }
            } else {
                TextButton(onClick = { friendsAscending = !friendsAscending }) {
                    Text("Sort: ${if (friendsAscending) "Name A-Z" else "Name Z-A"}", color = Green)
                }
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(visibleTraders, key = { _, trader -> trader.id }) { index, trader ->
                    TraderRow(trader, if (selectedTab == 0) index + 1 else null)
                    HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                }
            }
        }
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
private fun AddFriendDialog(onDismiss: () -> Unit) {
    var friendInput by rememberSaveable { mutableStateOf("") }
    var showPreviewMessage by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Add friend", color = Color.White)
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.semantics { contentDescription = "Close add friend dialog" },
                ) { Text("X", color = Muted) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your username: @demo_trader", color = Green)
                Text("Your ID: demo-001 (sample account)", color = Muted)
                OutlinedTextField(
                    value = friendInput,
                    onValueChange = {
                        friendInput = it
                        showPreviewMessage = false
                    },
                    label = { Text("Friend ID or username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Green,
                        unfocusedLabelColor = Muted,
                        focusedBorderColor = Green,
                        unfocusedBorderColor = Muted,
                        cursorColor = Green,
                    ),
                )
                Text(
                    if (showPreviewMessage) "Preview only: no request was sent. Friend requests will work once the backend is connected."
                    else "Enter a friend's ID or username. This screen currently uses sample data.",
                    color = Muted,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { showPreviewMessage = true },
                enabled = friendInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Background),
            ) { Text("Add friend") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Green) }
        },
    )
}

@Composable
private fun TraderRow(trader: SocialTrader, rank: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(if (trader.isYou) Green.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 4.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (rank != null) {
            Text(
                rank.toString(),
                color = if (rank <= 3) Color(0xFFE3BE68) else Muted,
                modifier = Modifier.width(20.dp),
                fontWeight = FontWeight.Bold,
            )
        }
        Box(
            modifier = Modifier.size(40.dp).background(Green.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(trader.name.split(" ").map { it.first() }.joinToString(""), color = Green)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(trader.name, color = Color.White, fontWeight = FontWeight.Medium)
            Text(
                String.format(Locale.US, "%+.1f%%", trader.returnPercent),
                color = if (trader.returnPercent >= 0) Green else Color(0xFFF08070),
                fontSize = 13.sp,
            )
        }
        Text(
            NumberFormat.getCurrencyInstance(Locale.US).apply {
                maximumFractionDigits = 0
            }.format(trader.totalValue),
            color = Color.White,
            fontSize = 14.sp,
        )
    }
}
