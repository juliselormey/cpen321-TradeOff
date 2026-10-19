package com.example.cpen321application

import android.R
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import io.socket.client.IO
import io.socket.client.Socket
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.awaitResponse
import java.net.Inet4Address
import android.net.ConnectivityManager
import android.net.Uri
import android.transition.CircularPropagation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.material3.Button
import com.example.cpen321application.ui.social.SocialScreen
import com.example.cpen321application.ApiClient.createSocket
import com.example.cpen321application.ui.portfolio.PortfolioScreen

import com.example.cpen321application.ui.market.MarketScreen
import com.example.cpen321application.ui.trade.TradeScreen

class MainActivity : ComponentActivity() {
    private lateinit var apiService: ApiService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        apiService = ApiClient.create(applicationContext)
        enableEdgeToEdge()
        setContent {
            CPEN321ApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = Routes.home,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable(Routes.home) {
                            HomeScreen(navController = navController)
                        }

                        composable(Routes.Login) {
                            LoginScreen(apiService = apiService, navController = navController)
                        }

                        composable(Routes.PixelScreen) {
                            PixelScreen(navController = navController)
                        }

                        composable(Routes.Social) {
                            SocialScreen(onBack = { navController.popBackStack() })
                        }

                        composable(Routes.Timer) {
                            TimerScreen(navController = navController)
                        }

                        composable(Routes.Portfolio) {
                            PortfolioScreen(onBack = { navController.popBackStack() })
                        composable(Routes.Market) {
                            MarketScreen(
                                onStockSelected = { ticker ->
                                    navController.navigate("Trade/$ticker")
                                }
                            )
                        }

                        composable(Routes.Trade) { backStackEntry ->
                            val ticker = backStackEntry.arguments?.getString("ticker") ?: ""
                            TradeScreen(ticker = ticker)
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

data class PixelUpdates(
    val x: Int,
    val y: Int,
    val color: String)

object Routes {
    const val home = "home"
    const val Login = "Login"
    const val PixelScreen = "PixelScreen"
    const val Timer = "Timer"
    const val Social = "Social"
    const val Portfolio = "Portfolio"
    const val Market = "Market"
    const val Trade = "Trade/{ticker}"
}

@Composable
fun HomeScreen(navController: NavController) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MyButton("Social", onClick = { navController.navigate(Routes.Social) })
        Spacer(modifier = Modifier.padding(16.dp))
        MyButton("Login", onClick = { navController.navigate(Routes.Login) })
        Spacer(modifier = Modifier.padding(16.dp))
        MyButton("Pixel Screen", onClick = { navController.navigate(Routes.PixelScreen) })
        Spacer(modifier = Modifier.padding(16.dp))
        MyButton("Timer", onClick = { navController.navigate(Routes.Timer) })
        Spacer(modifier = Modifier.padding(16.dp))
        MyButton("Portfolio", onClick = { navController.navigate(Routes.Portfolio) })
        MyButton("Market", onClick = { navController.navigate(Routes.Market) })
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
        MyButton("Back to Home") {
            navController.navigate(Routes.home) {
                popUpTo(Routes.home) { inclusive = true }
            }
        }
    }
}

/*--------------------------End Login-----------------------------*/

/*--------------------------Start Timer-----------------------------*/
@Composable
fun TimerScreen(navController: NavController) {
    var hoursInput by remember { mutableStateOf("")}
    var minutesInput by remember { mutableStateOf("")}
    var secondsInput by remember { mutableStateOf("")}

    var remainingSeconds by remember { mutableStateOf<Int?>(null)}
    var isRunning by remember { mutableStateOf(false)}
    var context = LocalContext.current

    LaunchedEffect(isRunning) {
        if (isRunning) {
            while (remainingSeconds != 0) {
                delay(1000)
                remainingSeconds = (remainingSeconds ?: 1) -1
            }
            if (remainingSeconds == 0) {
                isRunning = false
                triggerSurprise(context)
            }
        }
    }

    Column (modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center){
        if (!isRunning) {
            OutlinedTextField(value = hoursInput,
                onValueChange = { hoursInput = it.filter{c -> c.isDigit()} },
                label = {Text("Hours")},
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = minutesInput,
                onValueChange = { minutesInput = it.filter{c -> c.isDigit()} },
                label = {Text("Minutes")},
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = secondsInput,
                onValueChange = { secondsInput = it.filter{c -> c.isDigit()} },
                label = {Text("Seconds")},
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Spacer(modifier = Modifier.height(16.dp))

            MyButton("Start Timer") {
                val hours = hoursInput.toIntOrNull() ?: 0
                val mins = minutesInput.toIntOrNull() ?: 0
                val secs = secondsInput.toIntOrNull() ?: 0
                val total = hours * 3600 + mins * 60 + secs

                if (total > 0) {
                    remainingSeconds = total
                    isRunning = true
                }
            }
        } else {
            val displayHours = (remainingSeconds ?: 0)/ 3600
            val displayMinutes = (remainingSeconds ?: 0) / 60
            val displaySeconds = (remainingSeconds ?: 0) % 60

            Text(
                text = String.format("%02d:%02d:%02d", displayHours, displayMinutes, displaySeconds),
                fontSize = 30.sp,
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
        MyButton("Back to Home") {
            navController.navigate(Routes.home) {
                popUpTo(Routes.home) { inclusive = true }
            }
        }
    }
}

fun triggerSurprise(context: Context) {
    val videoId =  "s5Z1CUC2qTo"
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId"))
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))

    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        context.startActivity(webIntent)
    }
}
/*--------------------------End Timer-----------------------------*/


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

/*--------------------------Start Pixels-----------------------------*/
@Composable
fun PixelGridView(gridState: PixelGridState) {
    val cellSize = 20.dp

    Column {
        for (y in 0..15) {
            Row {
                for (x in 0..15) {
                    val index = y*16 + x
                    Box(
                        modifier = Modifier.size(cellSize).background(gridState.grid[index])

                    )
                }
            }
        }
    }
}

@Composable
fun PixelScreen(navController: NavController) {
    val context = LocalContext.current
    val gridState = remember { PixelGridState() }
    var connectionError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val socket = createSocket(context)
        socket.on(Socket.EVENT_CONNECT) {
            Log.d("PIXEL_DEBUG", "connected")
        }

        socket.on("pixel") { args ->
            if (args.isNotEmpty()) {
                val data = args[0] as JSONObject
                val x = data.getInt("x")
                val y = data.getInt("y")
                val color = data.getString("color")
                gridState.setPixel(x, y, color)
            }
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            connectionError = "Connection error: ${args.getOrNull(0)}"
            Log.e("SOCKET_DEBUG", "Connection error: ${args.getOrNull(0)}")
        }

        socket.connect()

        onDispose {
            socket.disconnect()
            socket.off()
        }

    }

    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        connectionError?.let { Text(it, color = Color.Red) }
        PixelGridView(gridState = gridState)
        Spacer(modifier = Modifier.height(32.dp))
        MyButton("Back to Home") {
            navController.navigate(Routes.home) {
                popUpTo(Routes.home) { inclusive = true }
            }
        }
    }

}


class PixelGridState {
    val grid = mutableStateListOf<Color>().apply() {
        repeat(16 * 16) {add(Color.White)}
    }

    fun setPixel(x: Int, y: Int, color: String) {
        if (x in 0..15 && y in 0..15) {
            val index = y*16 + x
            grid[index] = try {
                Color(android.graphics.Color.parseColor(color))
            } catch (e: Exception) {
                Color.Gray
            }
        }
    }

    fun clear() {
        for (i in grid.indices) {
            grid[i] = Color.White
        }
    }
}

/*--------------------------End Pixels-----------------------------*/


// HELPERS

suspend fun googleSignIn(context: Context): String { //check if right context was imported
    val credentialManager = CredentialManager.create(context)
    val googleIdOption = GetSignInWithGoogleOption.Builder(
        serverClientId = "417938142085-ngdhrek8t9vggugpbcadcjbcrn7vb5r3.apps.googleusercontent.com"
    ).build()

    val request = GetCredentialRequest.Builder().addCredentialOption(googleIdOption).build()

    try {
        val response = credentialManager.getCredential(request = request, context = context)
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

    return "unknown"
}

fun getClientTime(): String {
    val now = java.util.Calendar.getInstance()
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    val timeStr = sdf.format(now.time)

    val offsetms  = now.get(java.util.Calendar.ZONE_OFFSET) + now.get(java.util.Calendar.DST_OFFSET)
    val offsetHours = offsetms / (1000 * 60 * 60)
    val sign = if (offsetHours >= 0) "+" else "-"
    val offsetStr = String.format("%02d", kotlin.math.abs(offsetHours))

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
