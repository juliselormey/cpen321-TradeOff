package com.example.cpen321application

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
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
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.awaitResponse
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import com.example.cpen321application.util.SessionManager

data class BottomNavItem(
    val title: String,
    val route: String,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {
    private lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apiService = ApiClient.create(applicationContext)
        enableEdgeToEdge()
        setContent {
            CPEN321ApplicationTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val topLevelRoutes = remember {
                    listOf(
                        BottomNavItem("Portfolio", Routes.Portfolio, Icons.Default.AccountBox),
                        BottomNavItem("Market", Routes.Market, Icons.Default.ShoppingCart),
                        BottomNavItem("Social", Routes.Social, Icons.Default.Person)
                    )
                }

                val showBottomBar = currentRoute in topLevelRoutes.map { it.route }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (showBottomBar) {
                            NavigationBar {
                                topLevelRoutes.forEach { item ->
                                    val selected = currentRoute == item.route
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            if (currentRoute != item.route) {
                                                navController.navigate(item.route) {
                                                    popUpTo(Routes.Market) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = item.title
                                            )
                                        },
                                        label = { Text(item.title) }
                                    )
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = Routes.Market,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Routes.Portfolio) {
                            ProtectedView(apiService = apiService) {
                                PortfolioScreen(onBack = {
                                    navController.navigate(Routes.Market) {
                                        popUpTo(Routes.Market) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                })
                            }
                        }

                        composable(Routes.Market) {
                            MarketScreen(
                                onStockSelected = { ticker ->
                                    navController.navigate("Trade/$ticker")
                                }
                            )
                        }

                        composable(Routes.Social) {
                            ProtectedView(apiService = apiService) {
                                SocialScreen(onBack = {
                                    navController.navigate(Routes.Market) {
                                        popUpTo(Routes.Market) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                })
                            }
                        }

                        composable(Routes.Trade) { backStackEntry ->
                            val ticker = backStackEntry.arguments?.getString("ticker") ?: ""
                            TradeScreen(ticker = ticker)
                        }

                        composable(Routes.Login) {
                            val context = LocalContext.current
                            var loggedIn by remember { mutableStateOf(SessionManager.isLoggedIn(context)) }

                            if (loggedIn) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text("You are signed in!", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    MyButton("Sign Out") {
                                        SessionManager.clearSession(context)
                                        loggedIn = false
                                    }
                                }
                            } else {
                                LoginView(
                                    apiService = apiService,
                                    onSuccess = {
                                        navController.navigate(Routes.Market) {
                                            popUpTo(Routes.Market) { inclusive = true }
                                        }
                                    }
                                )
                            }
                        }

                        composable(Routes.home) {
                            HomeScreen(navController = navController)
                        }
                    }
                }
            }
        }
    }
}

data class LoginInfo (
    val serverIp: String = "",
    val clientIp: String = "",
    val serverTime: String = "",
    val clientTime: String = "",
    val backendOwnerName: String = "",
    val googleUserName: String = "",
)

object Routes {
    const val home = "home"
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

@Composable
fun LoginView(
    apiService: ApiService,
    onSuccess: () -> Unit = {}
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

        if (isLoading) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Signing in with Google...", color = Color.Gray, fontSize = 14.sp)
        } else {
            Button(
                onClick = {
                    scope.launch {
                        isLoading = true
                        errorMsg = null
                        try {
                            val signInResult = googleSignIn(context)
                            val authResponse = apiService.loginWithGoogle(GoogleAuthRequest(signInResult.idToken))

                            if (authResponse.isSuccessful && authResponse.body() != null) {
                                val token = authResponse.body()!!.accessToken
                                SessionManager.saveToken(context, token)
                                onSuccess()
                            } else {
                                val errorBody = authResponse.errorBody()?.string()
                                Log.e("AUTH_ERROR", "Sign-in failed with HTTP ${authResponse.code()}: $errorBody")
                                errorMsg = "Sign-in failed: ${authResponse.code()}"
                            }
                        } catch (e: Exception) {
                            errorMsg = e.message ?: "Sign-in cancelled or failed"
                        } finally {
                            isLoading = false
                        }
                    }
                },
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

        errorMsg?.let { msg ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = msg, color = Color.Red, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
    }
}

/*--------------------------End Login-----------------------------*/

private suspend fun fetchHealthStatus(apiBaseUrl: String): String = withContext(Dispatchers.IO) {
    val healthUrl = "${apiBaseUrl.trimEnd('/')}/health"
    try {
        val connection = (URL(healthUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 5_000
        }

        when (val code = connection.responseCode) {
            HttpURLConnection.HTTP_OK -> {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                "Backend healthy ($healthUrl): $body"
            }
            else -> {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                "Backend error ($healthUrl): HTTP $code${errorBody?.let { " — $it" } ?: ""}"
            }
        }
    } catch (e: Exception) {
        "Backend unreachable ($healthUrl): ${e.message ?: e.javaClass.simpleName}"
    }
}

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

        throw Exception("Invalid credential type: ${credential.type}")

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

