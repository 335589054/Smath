package com.smath.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val DeepBlue = Color(0xFF4D6BFE)
val DeepBlueDark = Color(0xFF8EA2FF)

private val LightColors = lightColorScheme(
    primary = DeepBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9EDFF),
    onPrimaryContainer = Color(0xFF1C2F80),
    secondary = Color(0xFF44506A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDF0F7),
    onSecondaryContainer = Color(0xFF2A3345),
    background = Color(0xFFF7F8FB),
    onBackground = Color(0xFF171A21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A21),
    surfaceVariant = Color(0xFFF0F2F7),
    onSurfaceVariant = Color(0xFF5C6474),
    outline = Color(0xFFE2E6EF),
    outlineVariant = Color(0xFFEDF0F5),
    error = Color(0xFFD9485F),
    errorContainer = Color(0xFFFFEDF0),
    onErrorContainer = Color(0xFF7A1224)
)

private val DarkColors = darkColorScheme(
    primary = DeepBlueDark,
    onPrimary = Color(0xFF0E1B4D),
    primaryContainer = Color(0xFF2B3766),
    onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Color(0xFFBBC4DA),
    onSecondary = Color(0xFF222A3A),
    secondaryContainer = Color(0xFF2A3141),
    onSecondaryContainer = Color(0xFFDCE2F0),
    background = Color(0xFF101218),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF171A22),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA5ADBC),
    outline = Color(0xFF353B49),
    outlineVariant = Color(0xFF272C37),
    error = Color(0xFFFF8FA2),
    errorContainer = Color(0xFF4E1C27),
    onErrorContainer = Color(0xFFFFD9DF)
)

private val SmathTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 30.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
fun SmathTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalContext.current as? Activity
    SideEffect {
        view?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = SmathTypography,
        content = content
    )
}