package com.pronote.clay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * AppTheme — the single centralised theme.
 *
 * Supports light, dark and Material You dynamic colour, plus an opt-out that
 * keeps the pastel palette even when dynamic colour is available. Every
 * SurfaceContainer role is set explicitly so the tonal cards and the frosted
 * chrome keep their hierarchy with dynamic colour off.
 */
private val LightColors = lightColorScheme(
    primary = ClayPrimaryLight,
    onPrimary = ClayOnPrimaryLight,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF24124A),
    secondary = ClaySecondaryLight,
    onSecondary = ClayOnSurfaceLight,
    secondaryContainer = Color(0xFFEDE5FB),
    onSecondaryContainer = ClayOnSurfaceLight,
    tertiary = ClayAccentLight,
    onTertiary = ClayOnSurfaceLight,
    tertiaryContainer = Color(0xFFD2F4EC),
    onTertiaryContainer = Color(0xFF0C3B34),
    background = ClayBackgroundLight,
    onBackground = ClayOnSurfaceLight,
    surface = ClaySurfaceLight,
    onSurface = ClayOnSurfaceLight,
    surfaceVariant = ClaySecondaryLight,
    onSurfaceVariant = ClayMutedLight,
    surfaceTint = ClayPrimaryLight,
    surfaceContainerLowest = Color(0xFFFFFDFF),
    surfaceContainerLow = Color(0xFFF8F4FF),
    surfaceContainer = Color(0xFFF2ECFC),
    surfaceContainerHigh = Color(0xFFECE4F9),
    surfaceContainerHighest = Color(0xFFE6DCF5),
    outline = ClayMutedLight,
    outlineVariant = Color(0xFFD8CFEA),
    error = ClayErrorLight,
    errorContainer = Color(0xFFFFE3DF),
    onErrorContainer = Color(0xFF4A0B07),
)

private val DarkColors = darkColorScheme(
    primary = ClayPrimaryDark,
    onPrimary = ClayOnPrimaryDark,
    primaryContainer = Color(0xFF4A3A72),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = ClaySecondaryDark,
    onSecondary = ClayOnSurfaceDark,
    secondaryContainer = Color(0xFF3A3352),
    onSecondaryContainer = ClayOnSurfaceDark,
    tertiary = ClayAccentDark,
    onTertiary = Color(0xFF06231F),
    tertiaryContainer = Color(0xFF2C4F49),
    onTertiaryContainer = Color(0xFFD2F4EC),
    background = ClayBackgroundDark,
    onBackground = ClayOnSurfaceDark,
    surface = ClaySurfaceDark,
    onSurface = ClayOnSurfaceDark,
    surfaceVariant = ClaySecondaryDark,
    onSurfaceVariant = ClayMutedDark,
    surfaceTint = ClayPrimaryDark,
    surfaceContainerLowest = Color(0xFF1B1728),
    surfaceContainerLow = Color(0xFF272134),
    surfaceContainer = Color(0xFF2C263B),
    surfaceContainerHigh = Color(0xFF332C44),
    surfaceContainerHighest = Color(0xFF3B334E),
    outline = Color(0xFF8F85A8),
    outlineVariant = Color(0xFF443C58),
    error = ClayErrorDark,
    errorContainer = Color(0xFF5A1F1B),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun AppTheme(
    /** null follows the system setting. */
    darkTheme: Boolean? = null,
    dynamicColor: Boolean = true,
    /** Réglage d'accessibilité : aucune apparition, aucun glissement. */
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = darkTheme ?: isSystemInDarkTheme()

    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }

    // En sombre, l'écriture est blanche : les rôles de texte ne dépendent plus
    // du fond tonal de la carte, donc un titre reste lisible partout.
    val colors = if (dark) {
        base.copy(
            onBackground = ClayTextDark,
            onSurface = ClayTextDark,
            onSurfaceVariant = ClayTextMutedDark,
            surface = ClaySurfaceDark,
            surfaceContainerLow = Color(0xFF2A2438),
            surfaceContainer = Color(0xFF2F2940),
            surfaceContainerHigh = Color(0xFF373047),
            surfaceContainerHighest = Color(0xFF3F3752),
        )
    } else {
        base
    }

    MaterialTheme(
        colorScheme = colors,
        typography = ClayTypography,
        shapes = ClayShapes,
    ) {
        // The glass tints are derived from the resolved scheme, so dynamic
        // colour flows into the frosted chrome instead of fighting it.
        CompositionLocalProvider(
            LocalFrosted provides rememberFrostedTokens(),
            LocalReduceMotion provides reduceMotion,
            content = content,
        )
    }
}

/** Shared clay dimensions. */
object ClayDimens {
    val ScreenPadding = 20.dp
    val CardPadding = 20.dp
    val SectionGap = 24.dp
    val ItemGap = 12.dp
    val MinTouchTarget = 48.dp
}
