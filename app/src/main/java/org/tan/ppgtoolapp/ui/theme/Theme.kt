package org.tan.ppgtoolapp.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = PpgGreen,
    secondary = PpgBlue,
    tertiary = PpgRed,
    background = PpgDarkBg,
    surface = PpgDarkSurface,
    onPrimary = White,
    onSecondary = White,
    onBackground = PpgDarkText,
    onSurface = PpgDarkText,
)

private val LightColorScheme = lightColorScheme(
    primary = PpgGreen,
    secondary = PpgBlue,
    tertiary = PpgRed,
    background = White,
    surface = PpgLightSurface,
    onPrimary = White,
    onSecondary = White,
    onBackground = PpgLightText,
    onSurface = PpgLightText,
)

@Composable
fun PPGToolTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
