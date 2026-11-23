package com.feiji.bulletnotes.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * 持久化用户的深浅色主题偏好：
 * - 首次未手动切换时自动跟随系统深色模式；
 * - 手动切换后持久保存，重启应用与闹钟响铃页均保持一致。
 */
object ThemePreferences {
    private const val PREFS_NAME = "feiji_theme_prefs"
    private const val KEY_DARK_THEME = "dark_theme_override"

    fun resolveDarkTheme(context: Context, systemDark: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_DARK_THEME)) {
            prefs.getBoolean(KEY_DARK_THEME, systemDark)
        } else {
            systemDark
        }
    }

    fun saveDarkTheme(context: Context, darkTheme: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_THEME, darkTheme)
            .apply()
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimaryFill,
    onPrimary = DarkBackground,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkContent,
    secondary = DarkContentSecondary,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceContainerHigh,
    onSecondaryContainer = DarkContent,
    tertiary = DarkContentSecondary,
    onTertiary = DarkBackground,
    background = DarkBackground,
    onBackground = DarkContent,
    surface = DarkSurface,
    onSurface = DarkContent,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkContentSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkBackground,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkError,
    inverseSurface = DarkContent,
    inverseOnSurface = DarkBackground,
    inversePrimary = DarkBackground
)

private val LightColorScheme = lightColorScheme(
    primary = LightContent,
    onPrimary = LightBackground,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightContent,
    secondary = LightContentSecondary,
    onSecondary = LightBackground,
    secondaryContainer = LightSurfaceVariant,
    onSecondaryContainer = LightContent,
    tertiary = LightContentSecondary,
    onTertiary = LightBackground,
    background = LightBackground,
    onBackground = LightContent,
    surface = LightSurface,
    onSurface = LightContent,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightContentSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = Color(0xFFFFFFFF),
    errorContainer = LightErrorContainer,
    onErrorContainer = LightError,
    inverseSurface = LightContent,
    inverseOnSurface = LightBackground,
    inversePrimary = LightBackground
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun FeiJiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                window.decorView.setBackgroundColor(colorScheme.background.toArgb())
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
    }
}
