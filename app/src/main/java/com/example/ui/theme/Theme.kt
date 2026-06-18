package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = PrimarySafetyYellow,
    secondary = SecondaryTactileCyan,
    tertiary = TertiaryAmber,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    error = EmergencyCrimson
)

// For accessibility, visually impaired apps are best kept in high-contrast dark mode to maximize screen brightness and contrast benefits
private val LightColorScheme = lightColorScheme(
    primary = PrimarySafetyYellow,
    secondary = SecondaryTactileCyan,
    tertiary = TertiaryAmber,
    background = Color.White,
    surface = Color(0xFFF5F5F5),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.Black,
    onSurface = Color.Black,
    error = EmergencyCrimson
)

@Composable
fun DrishtiTheme(
    darkTheme: Boolean = true, // Force dark theme by default for accessibility high contrast
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
