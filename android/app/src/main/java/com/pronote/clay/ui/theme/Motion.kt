package com.pronote.clay.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Vrai quand l'utilisateur a demandé moins de mouvement.
 *
 * Deux sources, une seule vérité : le réglage d'Ardoise, et l'échelle
 * d'animation du système à zéro (« Supprimer les animations » dans les options
 * de développement ou d'accessibilité). Un élève qui a coupé les animations
 * pour des raisons de confort ou de santé ne doit pas les subir dans l'app.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Lit l'échelle d'animation d'Android, sans dépendre de la classe Animation. */
fun systemAnimationsDisabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}.getOrDefault(false)
