package com.feiji.bulletnotes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentPrimary,
    background = DarkPaperBackground,
    surface = DarkCardBackground,
    onPrimary = CardBackground,
    onBackground = DarkTextPrimary,
    onSurface = DarkTextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = InkBlack,
    background = PaperBackground,
    surface = CardBackground,
    onPrimary = CardBackground,
    onBackground = InkBlack,
    onSurface = InkBlack
)

@Composable
fun FeiJiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
