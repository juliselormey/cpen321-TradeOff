package com.example.cpen321application

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun LoginView(
    apiService: ApiService,
    onSuccess: () -> Unit = {},
    signIn: suspend (Context) -> GoogleSignInResult = ::googleSignIn,
    onBack: () -> Unit,
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

        TextButton(onClick = onBack) {
            Text("Go back")
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
        text = "Sign in to trade, view your portfolio, and see social leaderboards.",
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
