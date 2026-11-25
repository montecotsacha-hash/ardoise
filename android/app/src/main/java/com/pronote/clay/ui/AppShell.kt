package com.pronote.clay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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

/** Hauteur intérieure de la barre, partagée par la pastille et les onglets. */
private val NavBarHeight = 52.dp

/**
 * Barre de navigation flottante : une seule pilule sur le contenu.
 *
 * La pastille de sélection **glisse** d'un onglet à l'autre (ressort), l'icône
 * choisie rebondit légèrement et son libellé s'ouvre en largeur au lieu
 * d'apparaître d'un coup. C'est le mouvement qui guide l'œil, pas la couleur.
 */
@Composable
private fun FloatingNavBar(
    section: AppSection,
    onSelect: (AppSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tabs = AppSection.entries

    // Position animée de la pastille, en unités d'onglet.
    val selection by animateFloatAsState(
        targetValue = section.ordinal.toFloat(),
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "nav-selection",
    )
    // La barre se compresse d'un cheveu pendant le déplacement, puis revient.
    val barScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "nav-bar-scale",
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .graphicsLayer {
                scaleX = barScale
                scaleY = barScale
            }
            .shadow(
                elevation = 16.dp,
                shape = CircleShape,
                ambientColor = scheme.primary.copy(alpha = 0.22f),
                spotColor = scheme.primary.copy(alpha = 0.26f),
            )
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        val tabWidth = maxWidth / tabs.size

        // Pastille unique, peinte SOUS les onglets : elle glisse latéralement.
        Box(
            Modifier
                .offset(x = tabWidth * selection)
                .width(tabWidth)
                .height(NavBarHeight)
                .clip(CircleShape)
                .background(scheme.primary),
        )

        Row(
            Modifier.fillMaxWidth().height(NavBarHeight),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { item ->
                NavTab(
                    item = item,
                    selected = item == section,
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    item: AppSection,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val content by animateColorAsState(
        targetValue = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        animationSpec = tween(220),
        label = "nav-content",
    )
    // Rebond de l'icône : un peu plus marqué que la pastille, pour la vie.
    val iconScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.86f
            selected -> 1.1f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "nav-icon-scale",
    )
    val iconLift by animateFloatAsState(
        targetValue = if (selected) -1.5f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "nav-icon-lift",
    )

    Row(
        modifier = modifier
            .height(NavBarHeight)
            .clip(CircleShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            item.icon,
            contentDescription = item.label,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                    translationY = iconLift
                },
            tint = content,
        )
        // Le libellé reste : une icône seule rend la navigation devinette. Il
        // s'ouvre en largeur (et non en fondu seul) pour accompagner la pastille.
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(180, delayMillis = 40)) +
                expandHorizontally(
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                    expandFrom = Alignment.Start,
                ),
            exit = fadeOut(tween(90)) + shrinkHorizontally(tween(120), shrinkTowards = Alignment.Start),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                Text(
                    item.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}
