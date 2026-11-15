package com.pause.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

val PauseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/**
 * Material 3 theme with a deliberate brand palette.
 *
 * Dynamic color is available as an opt-in, but is off by default: wallpaper
 * colors cannot guarantee the contrast and 60-30-10 hierarchy this app relies
 * on. The custom schemes are used on every API level, including the dark pause
 * surface.
 */
@Composable
fun PauseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> PauseDarkColors
        else -> PauseLightColors
    }
    val semanticColors = if (darkTheme) PauseDarkSemanticColors else PauseLightSemanticColors

    CompositionLocalProvider(LocalPauseSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PauseTypography,
            shapes = PauseShapes,
            content = content,
        )
    }
}
