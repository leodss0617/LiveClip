package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EditorColorScheme = darkColorScheme(
    primary = BrandPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF164B7F),
    onPrimaryContainer = Color(0xFFEAF4FF),
    secondary = BrandSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF6F292C),
    onSecondaryContainer = Color(0xFFFFE9E8),
    tertiary = BrandCyan,
    onTertiary = Color(0xFF001F35),
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = SurfaceBorder,
    error = RedError,
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = EditorColorScheme,
        typography = Typography,
        content = content
    )
}
