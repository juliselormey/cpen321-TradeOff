package com.example.cpen321application

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.DisposableEffect
import com.example.cpen321application.ui.settings.SettingsScreen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import androidx.credentials.exceptions.GetCredentialException
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.cpen321application.ui.market.MarketScreen
import com.example.cpen321application.ui.portfolio.PortfolioScreen
import com.example.cpen321application.ui.social.SocialScreen
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import com.example.cpen321application.ui.trade.TradeScreen
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.example.cpen321application.util.SessionManager

data class BottomNavItem(
    val title: String,
    val route: String,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val api = ApiClient.create(applicationContext)
        enableEdgeToEdge()
        setContent { TradeOffApp(api) }
    }
}

@Composable
private fun TradeOffApp(api: ApiService) {
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

private fun NavController.showMarket() {
    navigate(Routes.Market) {
        popUpTo(Routes.Market) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun AppDestinations(
    navController: androidx.navigation.NavHostController,
    api: ApiService,
    darkMode: Boolean,
    modifier: Modifier,
    onDarkModeChange: (Boolean) -> Unit
) {
    NavHost(navController, startDestination = Routes.Market, modifier = modifier) {
        composable(Routes.Portfolio) {
            ProtectedView(apiService = api) { PortfolioScreen(onBack = { navController.showMarket() }) }
        }
        composable(Routes.Market) {
            MarketScreen(onStockSelected = { navController.navigate("Trade/" + it) })
        }
        composable(Routes.Social) {
            ProtectedView(apiService = api) { SocialScreen(onBack = { navController.showMarket() }) }
        }
        composable(Routes.Settings) {
            SettingsScreen(api, darkMode, onDarkModeChange, onSignIn = { navController.navigate(Routes.Login) })
        }
        composable(Routes.Trade) { entry ->
            TradeScreen(ticker = entry.arguments?.getString("ticker").orEmpty())
        }
        composable(Routes.Login) {
            LoginView(apiService = api, onSuccess = {
                navController.navigate(Routes.Settings) {
                    popUpTo(Routes.Settings) { inclusive = true }
                    launchSingleTop = true
                }
            })
        }
        composable(Routes.home) { HomeScreen(navController) }
    }
}

object Routes {
    const val home = "home"
    const val Settings = "Settings"
    const val Login = "Login"
    const val Social = "Social"
    const val Portfolio = "Portfolio"
    const val Market = "Market"
    const val Trade = "Trade/{ticker}"
}

@Composable
fun HomeScreen(navController: NavController) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MyButton("Portfolio", onClick = { navController.navigate(Routes.Portfolio) })
        Spacer(modifier = Modifier.padding(16.dp))
        MyButton("Market", onClick = { navController.navigate(Routes.Market) })
        Spacer(modifier = Modifier.padding(16.dp))
        MyButton("Social", onClick = { navController.navigate(Routes.Social) })
        Spacer(modifier = Modifier.padding(16.dp))
        MyButton("Login", onClick = { navController.navigate(Routes.Login) })
    }

}


/*--------------------------Protected View-----------------------------*/
@Composable
fun ProtectedView(
    apiService: ApiService,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    var isUserLoggedIn by remember { mutableStateOf(SessionManager.isLoggedIn(context)) }

    DisposableEffect(context) {
        val preferences = context.getSharedPreferences("user_session", Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            isUserLoggedIn = SessionManager.isLoggedIn(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        isUserLoggedIn = SessionManager.isLoggedIn(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    if (!isUserLoggedIn) {
        LoginView(
            apiService = apiService,
            onSuccess = {
                isUserLoggedIn = true
            }
        )
    } else {
        content()
    }
}

/*--------------------------Start Login-----------------------------*/

private sealed interface LoginOutcome {
    object Success : LoginOutcome
    class Failure(val message: String) : LoginOutcome
}

private suspend fun attemptLogin(
    context: Context,
    apiService: ApiService,
    signIn: suspend (Context) -> GoogleSignInResult,
): LoginOutcome {
    return try {
        val signInResult = signIn(context)
        val response = apiService.loginWithGoogle(GoogleAuthRequest(signInResult.idToken))
        val body = response.body()

        if (response.isSuccessful && body != null) {
            SessionManager.saveToken(context, body.accessToken)
            LoginOutcome.Success
        } else {
            val errorBody = response.errorBody()?.string()
            Log.e("AUTH_ERROR", "Sign-in failed with HTTP ${response.code()}: $errorBody")
            LoginOutcome.Failure("Sign-in failed: ${response.code()}")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: GoogleIdTokenParsingException) {
        LoginOutcome.Failure("Could not read the Google credential. Please try signing in again.")
    } catch (e: GetCredentialException) {
        LoginOutcome.Failure(e.message ?: "Sign-in cancelled or failed")
    } catch (_: java.io.IOException) {
        LoginOutcome.Failure("Could not connect. Please try again.")
    } catch (_: com.google.gson.JsonParseException) {
        LoginOutcome.Failure("Could not read the sign-in response.")
    } catch (e: IllegalStateException) {
        LoginOutcome.Failure(e.message ?: "Invalid sign-in response")
    }
}

@Composable
fun LoginView(
    apiService: ApiService,
    onSuccess: () -> Unit = {},
    signIn: suspend (Context) -> GoogleSignInResult = ::googleSignIn
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        LoginHeader()

        if (isLoading) {
            LoginProgress()
        } else {
            GoogleSignInButton(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMsg = null
                        when (val outcome = attemptLogin(context, apiService, signIn)) {
                            is LoginOutcome.Success -> onSuccess()
                            is LoginOutcome.Failure -> errorMsg = outcome.message
                        }
                        isLoading = false
                    }
                }
            )
        }

        errorMsg?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = msg, color = Color.Red, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LoginHeader() {
    Text(
        text = "Welcome to TradeOff",
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Please log in to view your portfolio and social leaderboards.",
        color = Color.Gray,
        fontSize = 14.sp,
        textAlign = TextAlign.Center
    )
    Spacer(modifier = Modifier.height(28.dp))
}

@Composable
private fun LoginProgress() {
    CircularProgressIndicator()
    Spacer(modifier = Modifier.height(16.dp))
    Text("Signing in with Google...", color = Color.Gray, fontSize = 14.sp)
}

@Composable
private fun GoogleSignInButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF4285F4),
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
    ) {
        Text(text = "Sign in with Google", fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
/*--------------------------End Login-----------------------------*/

// HELPERS
/*-------------------------- Google Sign-In Helper -----------------------------*/
data class GoogleSignInResult(val idToken: String, val displayName: String)

suspend fun googleSignIn(context: Context): GoogleSignInResult {
    val credentialManager = CredentialManager.create(context)
    val serverClientId = BuildConfig.GOOGLE_CLIENT_ID.ifEmpty {
        "417938142085-ngdhrek8t9vggugpbcadcjbcrn7vb5r3.apps.googleusercontent.com"
    }

    val googleIdOption = GetSignInWithGoogleOption.Builder(
        serverClientId = serverClientId
    ).build()

    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    try {
        val response = try {
            credentialManager.getCredential(request = request, context = context)
        } catch (e: NoCredentialException) {
            Log.d("GOOGLE_DEBUG", "No authorized credentials found, falling back to all accounts", e)
            val fallbackOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .build()
            val fallbackRequest = GetCredentialRequest.Builder()
                .addCredentialOption(fallbackOption)
                .build()
            credentialManager.getCredential(request = fallbackRequest, context = context)
        }

        val credential = response.credential

        Log.d("GOOGLE_DEBUG", "Credential type: ${credential.type}")

        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val firstName = googleIdTokenCredential.givenName ?: ""
            val lastName = googleIdTokenCredential.familyName ?: ""
            val fullname = if(lastName.isNotEmpty()) "$firstName $lastName" else firstName

            return GoogleSignInResult(idToken = googleIdTokenCredential.idToken, displayName = fullname)
        }

        throw IllegalStateException("Invalid credential type: ${credential.type}")

    } catch (e: GetCredentialCancellationException) {
        Log.i("CredentialManagerException", "User Cancelled Sign-In", e)
        throw e
    } catch (e: GetCredentialException) {
        Log.e("CredentialManagerException", "Sign-In Failed", e)
        throw e
    }
}

@Composable
fun MyButton(buttonText: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Gray,
            contentColor = Color.White
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Text(text = buttonText)
    }
}

