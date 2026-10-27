package com.vsk.attendance.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF1565C0)
private val BlueDark = Color(0xFF0D47A1)
private val Green = Color(0xFF2E7D32)
private val Amber = Color(0xFFF9A825)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    secondary = Green,
    tertiary = Amber
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF00315C),
    primaryContainer = BlueDark,
    secondary = Color(0xFF81C784),
    tertiary = Color(0xFFFFD54F)
)

@Composable
fun VSKTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}
