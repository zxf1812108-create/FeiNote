package com.feiji.bulletnotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = DarkContent,
    onPrimary = DarkBackground,
    primaryContainer = DarkBackground,
    onPrimaryContainer = DarkContent,
    secondary = DarkContent,
    onSecondary = DarkBackground,
    tertiary = DarkContent,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkContent,
    surface = DarkBackground,
    onSurface = DarkContent,
    surfaceVariant = DarkBackground,
    onSurfaceVariant = DarkContent,
    outline = DarkContent,
    outlineVariant = DarkContent,
    inverseSurface = DarkBackground,
    inverseOnSurface = DarkContent,
    inversePrimary = DarkContent
)

private val LightColorScheme = lightColorScheme(
    primary = LightContent,
    onPrimary = LightBackground,
    primaryContainer = LightBackground,
    onPrimaryContainer = LightContent,
    secondary = LightContent,
    onSecondary = LightBackground,
    tertiary = LightContent,
    onTertiary = LightBackground,
    background = LightBackground,
    onBackground = LightContent,
    surface = LightBackground,
    onSurface = LightContent,
    surfaceVariant = LightBackground,
    onSurfaceVariant = LightContent,
    outline = LightContent,
    outlineVariant = LightContent,
    inverseSurface = LightBackground,
    inverseOnSurface = LightContent,
    inversePrimary = LightContent
)

@Composable
fun FeiJiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

