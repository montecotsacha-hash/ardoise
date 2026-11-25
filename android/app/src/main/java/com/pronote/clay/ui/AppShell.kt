package com.pronote.clay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pronote.clay.data.remote.UpdateStatus
import com.pronote.clay.ui.components.UpdateAvailableDialog
import com.pronote.clay.ui.screens.HomeScreen
import com.pronote.clay.ui.screens.SettingsScreen
import com.pronote.clay.ui.screens.TimetableScreen
import com.pronote.clay.ui.viewmodel.HomeViewModel
import com.pronote.clay.ui.viewmodel.TimetableViewModel

/**
 * The three sections of the app.
 *
 * Navigation is a single floating bottom bar, like the system's own: one plane
 * of glass over the content, no drawer, nothing hidden behind a hamburger.
 */
enum class AppSection(val label: String, val icon: ImageVector) {
    Dashboard("Accueil", Icons.Rounded.Home),
    Timetable("Emploi du temps", Icons.Rounded.CalendarMonth),
    Settings("Réglages", Icons.Rounded.Settings),
}

/**
 * AppShell — the authenticated surface: the current section and the floating
 * navigation bar. It owns the only navigation state of the app, so a screen can
 * never be drawn under another one.
 */
@Composable
fun AppShell(viewModel: HomeViewModel) {
    var section by rememberSaveable { mutableStateOf(AppSection.Dashboard) }

    // Retour système : depuis une autre section, il ramène à l'accueil au lieu
    // de quitter l'application.
    BackHandler(enabled = section != AppSection.Dashboard) {
        section = AppSection.Dashboard
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = section,
            transitionSpec = {
                val forward = targetState.ordinal >= initialState.ordinal
                (
                    slideInHorizontally(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ) { if (forward) it / 10 else -it / 10 } +
                        fadeIn(spring(stiffness = Spring.StiffnessMediumLow))
                    ) togetherWith (
                    slideOutHorizontally(tween(180)) { if (forward) -it / 14 else it / 14 } +
                        fadeOut(tween(140))
                    )
            },
            label = "section",
            modifier = Modifier.fillMaxSize(),
        ) { target ->
            when (target) {
                AppSection.Dashboard -> HomeScreen(viewModel = viewModel)

                AppSection.Timetable -> {
                    // Activity-scoped on purpose: swiping between days and weeks
                    // never refetches after the first load. Fully qualified
                    // because the `viewModel` parameter shadows the factory.
                    val timetableViewModel: TimetableViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel {
                            TimetableViewModel(viewModel.container)
                        }
                    TimetableScreen(
                        viewModel = timetableViewModel,
                        onBack = { section = AppSection.Dashboard },
                    )
                }

                AppSection.Settings -> SettingsScreen(
                    viewModel = viewModel,
                    onBack = { section = AppSection.Dashboard },
                )
            }
        }

        FloatingNavBar(
            section = section,
            onSelect = { section = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // La proposition de mise à jour, au-dessus de la section courante : elle
        // ne disparaît pas en changeant d'écran. Le téléchargement se fait dans
        // l'app, puis l'installateur Android s'ouvre sur l'APK.
        val updateStatus by viewModel.updateStatus.collectAsState()
        val currentUpdate = updateStatus
        if (currentUpdate is UpdateStatus.Available && currentUpdate.promptVisible) {
            UpdateAvailableDialog(
                info = currentUpdate.info,
                onDismiss = { viewModel.dismissUpdate() },
            )
        }
    }
}

/** Barre de navigation flottante : une seule pilule de verre sur le contenu. */
@Composable
private fun FloatingNavBar(
    section: AppSection,
    onSelect: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                ambientColor = scheme.primary.copy(alpha = 0.28f),
                spotColor = scheme.primary.copy(alpha = 0.32f),
            )
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppSection.entries.forEach { item ->
            val selected = item == section
            val background by animateColorAsState(
                targetValue = if (selected) scheme.primary else Color.Transparent,
                animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                label = "nav-background",
            )
            val content by animateColorAsState(
                targetValue = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                animationSpec = tween(220),
                label = "nav-content",
            )
            val scale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.96f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                label = "nav-scale",
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clip(CircleShape)
                    .background(background)
                    .clickable(role = Role.Tab) { onSelect(item) }
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    modifier = Modifier.size(21.dp),
                    tint = content,
                )
                // Le libellé reste : une icône seule rend la navigation devinette.
                AnimatedVisibility(visible = selected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(9.dp))
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = content,
                        )
                    }
                }
            }
        }
    }
}
