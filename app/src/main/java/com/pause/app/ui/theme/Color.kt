package com.pause.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic colors that the Material 3 scheme does not model directly.
 *
 * Every value is paired with a container / on-container pair so status badges
 * keep >= 4.5:1 text contrast and never rely on hue alone (each status also
 * ships an icon and a text label).
 */
@Immutable
data class PauseSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

val PauseLightSemanticColors = PauseSemanticColors(
    success = Color(0xFF1E6B46),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD3EDDF),
    onSuccessContainer = Color(0xFF052417),
    warning = Color(0xFF7A5200),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFBEBC8),
    onWarningContainer = Color(0xFF291A00),
    info = Color(0xFF215D87),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFD6E8F6),
    onInfoContainer = Color(0xFF062133),
)

val PauseDarkSemanticColors = PauseSemanticColors(
    success = Color(0xFF8FD5AC),
    onSuccess = Color(0xFF00391F),
    successContainer = Color(0xFF1A4A31),
    onSuccessContainer = Color(0xFFC9F2D7),
    warning = Color(0xFFF2C66D),
    onWarning = Color(0xFF3D2C00),
    warningContainer = Color(0xFF57410A),
    onWarningContainer = Color(0xFFFFE2A3),
    info = Color(0xFFA9CDEB),
    onInfo = Color(0xFF05324D),
    infoContainer = Color(0xFF1A3D55),
    onInfoContainer = Color(0xFFD4E9F8),
)

val LocalPauseSemanticColors = staticCompositionLocalOf { PauseLightSemanticColors }

// 60% of the screen is the warm neutral background, 30% is the tonal card
// surface, and the pine primary is reserved for actions and small accents.
val PauseLightColors = lightColorScheme(
    primary = Color(0xFF1F5E46),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6EADF),
    onPrimaryContainer = Color(0xFF082318),
    inversePrimary = Color(0xFF9BD3B8),
    secondary = Color(0xFF5A615A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE4E7E0),
    onSecondaryContainer = Color(0xFF1B1F1B),
    tertiary = Color(0xFF76552A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3E2C7),
    onTertiaryContainer = Color(0xFF2A1C08),
    background = Color(0xFFF6F3EC),
    onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFF6F3EC),
    onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE3E0D8),
    onSurfaceVariant = Color(0xFF4A5049),
    surfaceTint = Color(0xFF1F5E46),
    surfaceBright = Color(0xFFFFFCF7),
    surfaceDim = Color(0xFFDFDBD3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCFAF4),
    surfaceContainer = Color(0xFFF0EDE5),
    surfaceContainerHigh = Color(0xFFE9E5DB),
    surfaceContainerHighest = Color(0xFFE1DDD2),
    inverseSurface = Color(0xFF2E312E),
    inverseOnSurface = Color(0xFFF1F0EA),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = Color(0xFF6E766E),
    outlineVariant = Color(0xFF7F897F),
    scrim = Color(0xFF000000),
)

val PauseDarkColors = darkColorScheme(
    primary = Color(0xFF9BD3B8),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF17513C),
    onPrimaryContainer = Color(0xFFC0EED5),
    inversePrimary = Color(0xFF1F5E46),
    secondary = Color(0xFFBFC6BA),
    onSecondary = Color(0xFF293029),
    secondaryContainer = Color(0xFF3B423B),
    onSecondaryContainer = Color(0xFFDBE2D8),
    tertiary = Color(0xFFE6C79A),
    onTertiary = Color(0xFF402C0B),
    tertiaryContainer = Color(0xFF5A4319),
    onTertiaryContainer = Color(0xFFFFE0A8),
    background = Color(0xFF10130F),
    onBackground = Color(0xFFE4E5DE),
    surface = Color(0xFF10130F),
    onSurface = Color(0xFFE4E5DE),
    surfaceVariant = Color(0xFF3E453E),
    onSurfaceVariant = Color(0xFFC0C7BE),
    surfaceTint = Color(0xFF9BD3B8),
    surfaceBright = Color(0xFF393C37),
    surfaceDim = Color(0xFF10130F),
    surfaceContainerLowest = Color(0xFF0A0D0A),
    surfaceContainerLow = Color(0xFF191C18),
    surfaceContainer = Color(0xFF1D211C),
    surfaceContainerHigh = Color(0xFF272B26),
    surfaceContainerHighest = Color(0xFF323630),
    inverseSurface = Color(0xFFE4E5DE),
    inverseOnSurface = Color(0xFF2E312E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8A948A),
    outlineVariant = Color(0xFF626C62),
    scrim = Color(0xFF000000),
)
