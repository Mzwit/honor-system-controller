package com.honor.systemcontroller.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Ok = Color(0xFF22A66E)
val Warn = Color(0xFFE5A100)
val Danger = Color(0xFFE5484D)
val Info = Color(0xFF3D7BFF)

private val Dark = darkColorScheme(
    primary = Color(0xFF6E9BFF), onPrimary = Color(0xFF00205F),
    primaryContainer = Color(0xFF1C3A7A), onPrimaryContainer = Color(0xFFDCE4FF),
    secondary = Color(0xFF5ED3B4), onSecondary = Color(0xFF00382C),
    background = Color(0xFF0E1116), onBackground = Color(0xFFE3E6EC),
    surface = Color(0xFF151A22), onSurface = Color(0xFFE3E6EC),
    surfaceVariant = Color(0xFF1F2632), onSurfaceVariant = Color(0xFFAEB6C4),
    surfaceContainer = Color(0xFF181E28), surfaceContainerHigh = Color(0xFF1E2531),
    outline = Color(0xFF3A4352), error = Danger
)

private val Light = lightColorScheme(
    primary = Color(0xFF2A5FE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF00195A),
    secondary = Color(0xFF00876A), onSecondary = Color.White,
    background = Color(0xFFF6F7FB), onBackground = Color(0xFF14181F),
    surface = Color.White, onSurface = Color(0xFF14181F),
    surfaceVariant = Color(0xFFE9ECF3), onSurfaceVariant = Color(0xFF4E5664),
    surfaceContainer = Color(0xFFF0F2F7), surfaceContainerHigh = Color(0xFFE8EBF2),
    outline = Color(0xFFC5CBD6), error = Danger
)

private val Type = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.3.sp)
)

@Composable
fun HonorTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}
