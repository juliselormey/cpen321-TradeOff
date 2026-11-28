package com.example.cpen321application

import androidx.compose.material.icons.filled.Settings
import com.example.cpen321application.ui.settings.SettingsScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.cpen321application.ui.market.MarketScreen
import com.example.cpen321application.ui.portfolio.PortfolioScreen
import com.example.cpen321application.ui.social.SocialScreen
import com.example.cpen321application.ui.trade.TradeScreen
import kotlinx.coroutines.launch
import com.example.cpen321application.notif.NotificationRegistration

private fun NavController.showMarket() {
    navigate(Routes.Market) {
        popUpTo(Routes.Market) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
internal fun AppDestinations(
    navController: androidx.navigation.NavHostController,
    api: ApiService,
    darkMode: Boolean,
    modifier: Modifier,
    onDarkModeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val signOut: () -> Unit = {
        scope.launch {
            NotificationRegistration.signOut(context)
            navController.navigate(Routes.Settings)
        }
    }

    val openTrade: (String) -> Unit = { ticker ->
        navController.navigate("Trade/" + ticker)
    }

    val goBack: () -> Unit = { navController.popBackStack() }

    NavHost(navController, startDestination = Routes.Market, modifier = modifier) {
        composable(Routes.Portfolio) {
            ProtectedView(apiService = api, onBack = goBack) {
                PortfolioScreen(
                    apiService = api,
                    onBack = { navController.showMarket() },
                    onStockSelected = openTrade,
                    onSignOut = signOut
                )
            }
        }

        composable(Routes.Market) {
            MarketScreen(
                api = api,
                onStockSelected = openTrade,
                onSignInRequired = { navController.navigate(Routes.Login) },
            )
        }

        composable(Routes.Social) {
            ProtectedView(apiService = api, onBack = goBack) {
                SocialScreen(
                    onBack = { navController.showMarket() },
                    onSignOut = signOut
                )
            }
        }

        composable(Routes.Settings) {
            SettingsScreen(
                api,
                darkMode,
                onDarkModeChange,
                onSignIn = { navController.navigate(Routes.Login) }
            )
        }

        composable(Routes.Trade) { entry ->
            ProtectedView(apiService = api, onBack = goBack) {
                TradeScreen(
                    ticker = entry.arguments?.getString("ticker").orEmpty(),
                    apiService = api,
                    onBack = goBack
                )
            }
        }

        composable(Routes.Login) {
            LoginView(
                apiService = api,
                onBack = goBack,
                onSuccess = {
                    navController.navigate(Routes.Market) {
                        popUpTo(Routes.Market) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }

    }
}

object Routes {
    const val Settings = "Settings"
    const val Login = "Login"
    const val Social = "Social"
    const val Portfolio = "Portfolio"
    const val Market = "Market"
    const val Trade = "Trade/{ticker}"
}
