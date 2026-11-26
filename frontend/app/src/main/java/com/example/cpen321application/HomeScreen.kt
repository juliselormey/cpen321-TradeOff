package com.example.cpen321application

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

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
