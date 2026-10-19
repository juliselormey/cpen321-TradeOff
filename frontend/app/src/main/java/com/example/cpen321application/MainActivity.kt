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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
                            PortfolioScreen(onBack = {
                                navController.navigate(Routes.Market) {
                                    popUpTo(Routes.Market) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            })
                        }

                        composable(Routes.Market) {
                            MarketScreen(
                                onStockSelected = { ticker ->
                                    navController.navigate("Trade/$ticker")
                                }
                            )
                        }

                        composable(Routes.Social) {
                            SocialScreen(onBack = {
                                navController.navigate(Routes.Market) {
                                    popUpTo(Routes.Market) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            })
                        }

                        composable(Routes.Trade) { backStackEntry ->
                            val ticker = backStackEntry.arguments?.getString("ticker") ?: ""
                            TradeScreen(ticker = ticker)
                        }

                        composable(Routes.Login) {
                            LoginScreen(apiService = apiService, navController = navController)
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

/*--------------------------Start Login-----------------------------*/
@Composable
fun LoginScreen(apiService: ApiService, navController: NavController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loginInfo by remember { mutableStateOf<LoginInfo?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        scope.launch {
            isLoading = true
            errorMsg = null

            try {
                val googleName = googleSignIn(context)
                val clientTime = getClientTime()
                val clientIp = getClientIp(context)

                val nameResponse = apiService.getName().awaitResponse()
                val ipResponse = apiService.getServerIp().awaitResponse()
                val timeResponse = apiService.getServerTime().awaitResponse()

                val nameBody = nameResponse.body()
                val ipBody = ipResponse.body()
                val backendFirstName = nameBody?.firstName ?: ""
                val backendLastName = nameBody?.lastName ?: ""
                val fullName = if (backendLastName.isNotEmpty()) "$backendFirstName $backendLastName" else backendFirstName

                loginInfo = LoginInfo (
                    serverIp = ipBody?.ip ?: "Unknown",
                    clientIp = clientIp,
                    serverTime = timeResponse.body()?.time ?: "Unknown",
                    clientTime = clientTime,
                    backendOwnerName = fullName.ifEmpty { "Unknown" },
                    googleUserName = googleName
                )
            } catch (e: Exception) {
                errorMsg = e.message ?: "Unknown error"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(loginInfo != null) {
        if (loginInfo != null) {
            while (true) {
                delay(1000)
                loginInfo = loginInfo?.let {
                    it.copy(
                        serverTime = incrementTimeString(it.serverTime),
                        clientTime = incrementTimeString(it.clientTime)
                    )
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (isLoading) {
            CircularProgressIndicator()
        }

        errorMsg?.let { Text(text = it, color = Color.Red) }

        loginInfo?.let { info ->
            Column {
                Text("Server IP: ${info.serverIp}")
                Text("Client IP: ${info.clientIp}")
                Text("Server Time: ${info.serverTime}")
                Text("Client Time: ${info.clientTime}")
                Text("Backend Owner: ${info.backendOwnerName}")
                Text("Logged in as: ${info.googleUserName}")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
        MyButton("Back to Market") {
            navController.navigate(Routes.Market) {
                popUpTo(Routes.Market) { inclusive = true }
            }
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

// HELPERS

suspend fun googleSignIn(context: Context): String {
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
            return if (lastName.isNotEmpty()) "$firstName $lastName" else firstName
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

fun getClientTime(): String {
    val now = Calendar.getInstance()
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
    val timeStr = sdf.format(now.time)

    val offsetms  = now.get(Calendar.ZONE_OFFSET) + now.get(Calendar.DST_OFFSET)
    val offsetHours = offsetms / (1000 * 60 * 60)
    val sign = if (offsetHours >= 0) "+" else "-"
    val offsetStr = String.format("%02d", abs(offsetHours))

    return "$timeStr GMT$sign$offsetStr:00"
}

fun getClientIp(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return "unknown"
    val linkProperties = cm.getLinkProperties(network) ?: return "unknown"

    for (linkAddress in linkProperties.linkAddresses) {
        val address = linkAddress.address
        if (address is Inet4Address && !address.isLoopbackAddress) {
            return address.hostAddress ?: "unknown"
        }
    }
    return "unknown"
}

fun incrementTimeString(timeStr: String): String {
    try {
        val parts = timeStr.split(" ")
        val hms = parts[0].split(":")
        var h = hms[0].toInt()
        var m = hms[1].toInt()
        var s = hms[2].toInt()

        s += 1
        if (s >= 60) {
            s = 0
            m += 1
            if (m >= 60) {
                m = 0
                h += 1
                if (h >= 24) h = 0
            }
        }
        val newHms = String.format("%02d:%02d:%02d", h, m, s)
        return timeStr.replaceFirst(parts[0], newHms)
    } catch (e: Exception) {
        return timeStr
    }
}
