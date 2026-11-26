package com.example.cpen321application

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme

data class BottomNavItem(
    val title: String,
    val route: String,
    val icon: ImageVector
)

@Composable
internal fun TradeOffApp(api: ApiService) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var darkMode by remember { mutableStateOf(preferences.getBoolean("dark_mode", false)) }
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val items = remember {
        listOf(
            BottomNavItem("Portfolio", Routes.Portfolio, Icons.Default.AccountBox),
            BottomNavItem("Market", Routes.Market, Icons.Default.ShoppingCart),
            BottomNavItem("Social", Routes.Social, Icons.Default.Person),
            BottomNavItem("Settings", Routes.Settings, Icons.Default.Settings)
        )
    }
    CPEN321ApplicationTheme(darkTheme = darkMode) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (items.any { it.route == entry?.destination?.route }) {
                    AppNavigationBar(items, entry?.destination?.route, navController)
                }
            }
        ) { padding ->
            AppDestinations(navController, api, darkMode, Modifier.padding(padding)) {
                darkMode = it
                preferences.edit().putBoolean("dark_mode", it).apply()
            }
        }
    }
}

@Composable
private fun AppNavigationBar(items: List<BottomNavItem>, currentRoute: String?, navController: NavController) {
    NavigationBar {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = {
                    if (currentRoute != item.route) {
                        navController.navigate(item.route) {
                            popUpTo(Routes.Market) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                },
                icon = { Icon(item.icon, contentDescription = item.title) },
                label = { Text(item.title) }
            )
        }
    }
}
