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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted layer — the Android 17 chrome of the app.
 *
 * Following the material-3-frosted placement rule, glass is only used for
 * chrome: the floating headers, the navigation drawer, the icon buttons and the
 * dialogs. Content cards stay on opaque SurfaceContainer tones.
 *
 * The blur is the enhancement, never the requirement: [FrostedTokens.tint] is
 * already opaque enough to keep text legible when the platform cannot blur
 * (API < 31, battery saver, the system "Reduce blur effects" toggle).
 */
@Immutable
data class FrostedTokens(
    /** Tint of a glass panel sitting over the ambient backdrop. */
    val tint: Color,
    /** Lighter tint for the floating chrome (headers, pills). */
    val chromeTint: Color,
    /** Hairline specular edge. */
    val border: Color,
    /** Vertical sheen painted over the tint. */
    val sheen: Color,
    /** Whether the platform can actually blur a window (API 31+). */
    val blurAvailable: Boolean,
    val dark: Boolean,
)

val LocalFrosted = compositionLocalOf {
    FrostedTokens(
        tint = Color.Transparent,
        chromeTint = Color.Transparent,
        border = Color.Transparent,
        sheen = Color.Transparent,
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
            sheen = if (dark) Color.White.copy(alpha = 0.06f) else Color.White.copy(alpha = 0.38f),
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

/** A glass panel: tinted surfaceContainer, vertical sheen, hairline specular edge. */
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
        .background(
            Brush.verticalGradient(
                0f to tokens.sheen,
                0.55f to Color.Transparent,
                1f to Color.Transparent,
            ),
        )
        .border(1.dp, tokens.border, shape)
}

/**
 * The ambient field behind every screen: soft radial blooms of the theme's
 * primary/tertiary/secondary over the background tone. It gives the translucent
 * chrome something to sit on, and replaces the flat single-colour background.
 */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminanceIsDark()
    val strength = if (dark) 0.55f else 0.46f

    Box(modifier.fillMaxSize().background(scheme.background)) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // Three overlapping blooms: the depth story starts here, under the
            // frosted chrome. Values are tuned so body text never sits on the
            // strongest part of a bloom at low contrast.
            bloom(scheme.primary, 0.92f, 0.02f, 0.78f, strength)
            bloom(scheme.tertiary, 0.02f, 0.92f, 0.85f, strength * 0.85f)
            bloom(scheme.secondary, -0.08f, 0.34f, 0.62f, strength * 0.55f)
            bloom(scheme.primary, 0.55f, 0.62f, 0.55f, strength * 0.22f)
        }
    }
}

private fun DrawScope.bloom(color: Color, x: Float, y: Float, radius: Float, alpha: Float) {
    val center = Offset(size.width * x, size.height * y)
    val r = size.minDimension * radius
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

internal fun Color.luminanceIsDark(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
