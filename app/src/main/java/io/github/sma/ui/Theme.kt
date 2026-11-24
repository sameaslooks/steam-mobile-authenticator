package io.github.sma.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val SteamBackground = Color(0xFF1B2838)
private val SteamSurface = Color(0xFF223447)
private val SteamPrimary = Color(0xFF66C0F4)
private val SteamText = Color(0xFFC7D5E0)
private val SteamMuted = Color(0xFF8F98A0)

private val SteamDarkScheme: ColorScheme = darkColorScheme(
    primary = SteamPrimary,
    onPrimary = SteamBackground,
    background = SteamBackground,
    onBackground = SteamText,
    surface = SteamSurface,
    onSurface = SteamText,
    surfaceVariant = SteamSurface,
    onSurfaceVariant = SteamMuted,
)

@Composable
fun SmaTheme(content: @Composable () -> Unit) {
    val colorScheme: ColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        SteamDarkScheme
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
