package com.pronote.clay.ui.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted layer — the Android 17 chrome of the app.
 *
 * Following the material-3-frosted placement rule, glass is only used for
 * chrome: the floating headers, the icon buttons and the dialogs. Content cards
 * stay on opaque SurfaceContainer tones. The tint is opaque enough to keep text
 * legible when the platform cannot blur (API < 31, battery saver, the system
 * "Reduce blur effects" toggle), and no gradient is painted over it: the glass
 * is a flat, calm plane.
 */
@Immutable
data class FrostedTokens(
    /** Tint of a glass panel sitting over the background. */
    val tint: Color,
    /** Lighter tint for the floating chrome (headers, pills). */
    val chromeTint: Color,
    /** Hairline specular edge. */
    val border: Color,
    /** Whether the platform can actually blur a window (API 31+). */
    val blurAvailable: Boolean,
    val dark: Boolean,
)

val LocalFrosted = compositionLocalOf {
    FrostedTokens(
        tint = Color.Transparent,
        chromeTint = Color.Transparent,
        border = Color.Transparent,
        blurAvailable = false,
        dark = false,
    )
}

/** Resolves the glass tints from the current colour scheme — never hardcoded. */
@Composable
fun rememberFrostedTokens(): FrostedTokens {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val blur = remember { canBlur(context) }
    return remember(scheme, blur) {
        val dark = scheme.surface.luminanceIsDark()
        FrostedTokens(
            tint = scheme.surfaceContainerHigh.copy(alpha = if (dark) 0.66f else 0.74f),
            chromeTint = scheme.surfaceContainer.copy(alpha = if (dark) 0.52f else 0.62f),
            border = if (dark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.65f),
            blurAvailable = blur,
            dark = dark,
        )
    }
}

/** True when the device can blur behind a window and is not in low-RAM mode. */
private fun canBlur(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
    if (!manager.isCrossWindowBlurEnabled) return false
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return activityManager?.isLowRamDevice != true
}

/** A glass panel: a flat tinted surface with a hairline specular edge. */
@Composable
fun Modifier.frosted(
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = LocalFrosted.current.chromeTint,
    elevation: Dp = 0.dp,
): Modifier {
    val tokens = LocalFrosted.current
    return this
        .then(
            if (elevation > 0.dp) {
                Modifier.shadow(
                    elevation = elevation,
                    shape = shape,
                    ambientColor = Color.Black.copy(alpha = 0.12f),
                    spotColor = Color.Black.copy(alpha = 0.16f),
                )
            } else {
                Modifier
            },
        )
        .clip(shape)
        .background(tint)
        .border(1.dp, tokens.border, shape)
}

/**
 * The field behind every screen: the theme's background tone, flat. The life
 * comes from the content and the navigation, not from a moving gradient.
 */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}

internal fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
