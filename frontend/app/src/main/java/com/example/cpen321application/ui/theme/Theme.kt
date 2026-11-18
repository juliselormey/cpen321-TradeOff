package com.example.cpen321application.ui.theme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF008D50), onPrimary = Color.White,
    background = Color(0xFFF6FAF8), onBackground = Color(0xFF14251E),
    surface = Color.White, onSurface = Color(0xFF14251E),
    surfaceVariant = Color(0xFFEEF5F0), onSurfaceVariant = Color(0xFF64716C),
    primaryContainer = Color(0xFFE0F2E7), onPrimaryContainer = Color(0xFF005C34),
    secondaryContainer = Color(0xFFE0F2E7), onSecondaryContainer = Color(0xFF005C34),
    error = Color(0xFFBE3345), errorContainer = Color(0xFFFFE9EC),
    outline = Color(0xFF64716C), outlineVariant = Color(0xFFE1EBE5),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEEF5F0)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF82D6A8), onPrimary = Color(0xFF00391F),
    background = Color(0xFF101814), onBackground = Color(0xFFE0EBE3),
    surface = Color(0xFF1A2520), onSurface = Color(0xFFE0EBE3),
    surfaceVariant = Color(0xFF27382E), onSurfaceVariant = Color(0xFFB0C3B7),
    primaryContainer = Color(0xFF244B35), onPrimaryContainer = Color(0xFFB2F1CB),
    secondaryContainer = Color(0xFF244B35), onSecondaryContainer = Color(0xFFB2F1CB),
    error = Color(0xFFFFB3BD), errorContainer = Color(0xFF51252D),
    outline = Color(0xFF84998C), outlineVariant = Color(0xFF34483C),
    surfaceContainer = Color(0xFF1A2520), surfaceContainerHigh = Color(0xFF27382E)
)
@Composable
fun CPEN321ApplicationTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography, content = content)
}
