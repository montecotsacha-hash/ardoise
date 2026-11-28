package com.pronote.clay.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.isActive
import kotlin.math.sin
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.pronote.clay.core.AverageCalculator
import com.pronote.clay.core.GradeTarget
import com.pronote.clay.core.HomeBlocks
import com.pronote.clay.domain.Grade
import com.pronote.clay.domain.HistoryPoint
import com.pronote.clay.domain.SchoolSnapshot
import com.pronote.clay.domain.Subject
import com.pronote.clay.domain.SubjectRow
import com.pronote.clay.ui.components.ClayButton
import com.pronote.clay.ui.components.ClayEmptyState
import com.pronote.clay.ui.components.ClayErrorBox
import com.pronote.clay.ui.components.ClayPill
import com.pronote.clay.ui.components.ClayReveal
import com.pronote.clay.ui.components.ClaySectionTitle
import com.pronote.clay.ui.components.ClaySurface
import com.pronote.clay.ui.components.clay
import com.pronote.clay.ui.theme.ClayDimens
import com.pronote.clay.ui.theme.LocalReduceMotion
import com.pronote.clay.ui.theme.subjectAccent
import com.pronote.clay.ui.viewmodel.HomeUiState
import com.pronote.clay.ui.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HomeScreen — the daily dashboard.
 *
 * Cache-first: the snapshot comes from local storage, so the screen is usable
 * even fully offline. Refreshing, offline and sync-error banners are derived
 * from the explicit [HomeUiState].
 *
 * Les blocs (moyenne, évolution, matières, notes, objectif) sont réordonnables :
 * un appui long sur un bloc le décolle, le glissement le place à la place d'un
 * autre, et l'ordre est mémorisé dans les préférences.
 *
 * The screen NEVER draws another screen on top of itself: the timetable and the
 * settings are full sections of their own, reached from the navigation menu.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
) {
    val state by viewModel.state.collectAsState()
    val online by viewModel.online.collectAsState()
    val periods by viewModel.periods.collectAsState()
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    // Courbe de l'année, tous trimestres déjà consultés confondus.
    val history by viewModel.history.collectAsState()
    val blocks by viewModel.homeBlocks.collectAsState()
    val attendance by viewModel.attendance.collectAsState()

    val success = state as? HomeUiState.Success
    val offline = state as? HomeUiState.Offline
    val issue = state as? HomeUiState.SyncIssue
    val error = state as? HomeUiState.Error

    val snapshot = success?.snapshot ?: offline?.snapshot ?: issue?.snapshot ?: error?.snapshot
    val average = success?.average ?: offline?.average ?: issue?.average
    val subjects = success?.subjects ?: offline?.subjects ?: issue?.subjects ?: error?.subjects.orEmpty()
    // Un trimestre sans note n'est pas une erreur : on le dit plutôt que de
    // laisser croire que la connexion a échoué.
    val periodEmptyHint = if (periods.size > 1) {
        "Aucune note dans ce trimestre : choisis une autre période ci-dessus."
    } else null

    // La note ouverte dans la fiche du bas. La liste ne montre que l'essentiel :
    // le détail vit ici, dans un panneau qui monte du bas.
    var selectedGrade by remember { mutableStateOf<Grade?>(null) }

    // ---- blocs déplaçables ------------------------------------------------
    //
    // Le glissement vit dans les coordonnées du *contenu* : chaque bloc retient
    // son haut mesuré, le doigt sa position absolue, et le décalage dessiné est
    // recalculé à chaque image à partir des deux. Réordonner la liste ne fait
    // donc jamais sauter le bloc tenu sous le doigt.
    val scrollState = rememberScrollState()
    val drag = remember { BlockDrag() }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val edge = with(density) { 96.dp.toPx() }
    val scrollStep = with(density) { 15.dp.toPx() }
    val blocksNow = rememberUpdatedState(blocks)
    val reorder = { drag.reorder(blocksNow.value, viewModel::moveHomeBlock) }

    // Tremblement des autres blocs, comme les apps sur l'écran d'accueil iOS.
    val reduceMotion = LocalReduceMotion.current
    val wobble: State<Float>? = if (drag.draggedId == null || reduceMotion) {
        null
    } else {
        rememberInfiniteTransition(label = "tremblement").animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 760, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
    }

    // Défilement automatique près des bords : sans lui, un bloc ne pouvait pas
    // traverser plus d'un écran. Le doigt est suivi en coordonnées de contenu,
    // donc le défilement se compense exactement.
    LaunchedEffect(drag.draggedId, viewportHeight, edge, scrollStep) {
        if (drag.draggedId == null || viewportHeight <= 0f) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            val finger = drag.dragY - scrollState.value
            val step = when {
                finger < edge -> -((edge - finger) / edge).coerceIn(0f, 1f) * scrollStep
                finger > viewportHeight - edge ->
                    ((finger - (viewportHeight - edge)) / edge).coerceIn(0f, 1f) * scrollStep
                else -> 0f
            }
            if (step == 0f) continue
            drag.dragY += scrollState.scrollBy(step)
            reorder()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The Activity draws edge-to-edge: keep the dashboard inside the
                // status-bar/display-cutout and navigation-bar safe areas.
                .safeDrawingPadding()
                .onSizeChanged { viewportHeight = it.height.toFloat() }
                .verticalScroll(scrollState)
                .padding(horizontal = ClayDimens.ScreenPadding),
        ) {
            ClayReveal { HomeTopBar(snapshot, viewModel) }
            Spacer(Modifier.height(18.dp))

            HomeStatusBanners(
                snapshot = snapshot,
                online = online,
                offline = offline != null,
                issue = issue,
                error = error,
            )

            if (periods.size > 1) {
                ClayReveal(index = 1) {
                    Column {
                        PeriodSelector(
                            periods = periods,
                            selectedId = selectedPeriod,
                            onSelect = viewModel::selectPeriod,
                        )
                        Spacer(Modifier.height(14.dp))
                    }
                }
            }

            blocks.forEach { id ->
                // La clé garde l'état de chaque bloc à sa place quand l'ordre
                // change : la période choisie dans « Évolution » ne saute pas
                // d'un bloc à l'autre après un déplacement.
                key(id) {
                    HomeBlockItem(
                        id = id,
                        wobble = wobble,
                        drag = drag,
                        onReorder = reorder,
                    ) {
                        HomeBlock(
                            id = id,
                            average = average,
                            snapshot = snapshot,
                            subjects = subjects,
                            history = history,
                            attendance = attendance,
                            periodLabel = periods.firstOrNull { it.id == selectedPeriod }?.name,
                            periodEmptyHint = periodEmptyHint,
                            onSelectGrade = { selectedGrade = it },
                        )
                    }
                }
            }

            // La barre de navigation flottante ne doit recouvrir aucun bloc.
            Spacer(Modifier.height(128.dp))
        }

        GradeDetailSheet(
            grade = selectedGrade,
            subject = selectedGrade?.let { grade ->
                snapshot?.subjects?.find { it.id == grade.subjectId }
            },
            subjectAverage = selectedGrade?.let { grade ->
                snapshot?.subjectAverages?.find { it.subjectId == grade.subjectId }
            },
            onDismiss = { selectedGrade = null },
        )
    }
}

/**
 * Un bloc de l'accueil, tenu ou non.
 *
 * Le nœud qui capte le geste reste à sa place dans la liste ; c'est un enfant
 * qui se décale à l'écran. Le décalage se recalcule depuis le haut mesuré du
 * bloc, donc un échange ne le fait pas sauter et un bloc plus haut que son
 * voisin ne saute pas plusieurs places d'un coup.
 */
@Composable
private fun HomeBlockItem(
    id: String,
    wobble: State<Float>?,
    drag: BlockDrag,
    onReorder: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dragging = drag.draggedId == id
    // Déphasage stable (l'index dans l'ordre par défaut), sinon la secousse
    // changerait d'amplitude à chaque déplacement.
    val phaseIndex = HomeBlocks.DEFAULT.indexOf(id).coerceAtLeast(0)

    Box(
        modifier = Modifier
            .onGloballyPositioned { coordinates ->
                val top = coordinates.positionInParent().y
                val height = coordinates.size.height.toFloat()
                if (drag.tops[id] != top) drag.tops[id] = top
                if (drag.heights[id] != height) drag.heights[id] = height
            }
            .zIndex(if (dragging) 1f else 0f)
            .pointerInput(id) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        drag.grab = offset.y
                        drag.dragY = (drag.tops[id] ?: 0f) + offset.y
                        drag.draggedId = id
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        // Position du doigt, pas un cumul de deltas : elle reste
                        // juste même quand la liste se réordonne ou défile.
                        val then = drag.dragY
                        drag.dragY = (drag.tops[id] ?: 0f) + change.position.y
                        drag.direction = drag.dragY - then
                        onReorder()
                    },
                    onDragEnd = { drag.end() },
                    onDragCancel = { drag.end() },
                )
            },
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                if (dragging) {
                    translationY = drag.dragY - drag.grab - (drag.tops[id] ?: 0f)
                    scaleX = 1.02f
                    scaleY = 1.02f
                    rotationZ = 0f
                } else {
                    translationY = 0f
                    scaleX = 1f
                    scaleY = 1f
                    val phase = wobble?.value
                    rotationZ = if (phase == null) {
                        0f
                    } else {
                        sin(phase + phaseIndex * 1.15f) * 1.5f
                    }
                }
            },
        ) {
            ClayReveal(index = 1) { content() }
        }
    }
}

/**
 * État du glissement d'un bloc.
 *
 * Tout est exprimé dans les coordonnées du contenu défilant : ni le défilement
 * ni l'ordre courant n'entrent dans le calcul, ce qui rend le geste stable.
 */
private class BlockDrag {
    var draggedId by mutableStateOf<String?>(null)

    /** Position du doigt, dans les coordonnées du contenu. */
    var dragY by mutableFloatStateOf(0f)

    /** Où le doigt a attrapé le bloc, depuis son haut. */
    var grab by mutableFloatStateOf(0f)

    /** Dernier déplacement du doigt : > 0 vers le bas, < 0 vers le haut. */
    var direction by mutableFloatStateOf(0f)

    val tops = mutableStateMapOf<String, Float>()
    val heights = mutableStateMapOf<String, Float>()

    fun end() {
        draggedId = null
        dragY = 0f
        grab = 0f
        direction = 0f
    }

    /**
     * Échange le bloc tenu avec le seul voisin dont le milieu est franchi.
     *
     * Deux garde-fous, sans lesquels le glissement sautait dans tous les sens :
     * un seul échange par image, et uniquement dans le sens du doigt. Juste
     * après un échange, le bloc échangé se retrouve collé au bloc tenu et son
     * milieu se trouve du mauvais côté : sans le sens du geste, le bloc tenu
     * repartait aussitôt en arrière, une image sur deux.
     */
    fun reorder(order: List<String>, move: (String, String) -> Unit) {
        val id = draggedId ?: return
        val step = direction
        if (step == 0f) return
        val from = order.indexOf(id)
        if (from < 0) return
        val centre = dragY - grab + (heights[id] ?: 0f) / 2f

        if (step > 0f) {
            val below = order.getOrNull(from + 1) ?: return
            val edge = tops[below] ?: return
            if (centre > edge + (heights[below] ?: 0f) / 2f) move(id, below)
        } else {
            val above = order.getOrNull(from - 1) ?: return
            val edge = tops[above] ?: return
            if (centre < edge + (heights[above] ?: 0f) / 2f) move(id, above)
        }
    }
}

/** Un bloc réordonnable de l'accueil, rendu seul pour que le glissement reste net. */
@Composable
private fun HomeBlock(
    id: String,
    average: com.pronote.clay.domain.AverageSummary?,
    snapshot: SchoolSnapshot?,
    subjects: List<SubjectRow>,
    history: List<HistoryPoint>,
    attendance: List<com.pronote.clay.domain.AttendanceEntry>,
    periodLabel: String?,
    periodEmptyHint: String?,
    onSelectGrade: (Grade) -> Unit,
) {
    when (id) {
        HomeBlocks.AVERAGE -> Column {
            if (average != null) {
                AverageCard(average, periodLabel = periodLabel)
            } else {
                ClayEmptyState(
                    "Pas encore de moyenne",
                    periodEmptyHint ?: "Connecte-toi et lance une synchronisation.",
                )
            }
            Spacer(Modifier.height(ClayDimens.SectionGap))
        }

        HomeBlocks.HISTORY -> HomeHistoryBlock(history)
        HomeBlocks.ATTENDANCE -> HomeAttendanceBlock(attendance)
        HomeBlocks.SUBJECTS -> HomeSubjectsBlock(subjects, periodEmptyHint)
        HomeBlocks.GRADES -> HomeGradesBlock(snapshot, periodEmptyHint, onSelectGrade)

        // L'objectif de moyenne ferme la page par défaut : c'est la question
        // qu'on se pose après avoir lu ses notes, pas avant.
        HomeBlocks.TARGET -> HomeTargetBlock(average?.value, snapshot)
    }
}

/**
 * « Vie scolaire » — absences et retards, les plus récents d'abord.
 *
 * Le bloc répond à la question qu'on se pose en ouvrant l'app (« ai-je manqué
 * quelque chose ? ») avant le détail : un total, puis quelques lignes.
 */
@Composable
private fun HomeAttendanceBlock(entries: List<com.pronote.clay.domain.AttendanceEntry>) {
    ClayReveal(index = 4) {
        Column {
            ClaySectionTitle("Vie scolaire")
            Spacer(Modifier.height(10.dp))
            if (entries.isEmpty()) {
                ClayEmptyState(
                    "Rien à signaler",
                    "Aucune absence ni retard enregistré pour l'instant.",
                )
            } else {
                ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 8.dp) {
                    Column(Modifier.padding(18.dp)) {
                        val absences = entries.count {
                            it.kind == com.pronote.clay.domain.AttendanceKind.ABSENCE
                        }
                        Text(
                            attendanceSummary(absences, entries.size - absences, entries),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        entries.sortedByDescending { it.sortKey }.take(4).forEach { entry ->
                            AttendanceRow(entry)
                        }
                    }
                }
            }
            Spacer(Modifier.height(ClayDimens.SectionGap))
        }
    }
}

/** « 3 absences · 2 retards · 6 h 30 manquées », au pluriel près. */
private fun attendanceSummary(
    absences: Int,
    delays: Int,
    entries: List<com.pronote.clay.domain.AttendanceEntry>,
): String {
    val parts = mutableListOf<String>()
    if (absences > 0) parts += if (absences == 1) "1 absence" else "$absences absences"
    if (delays > 0) parts += if (delays == 1) "1 retard" else "$delays retards"
    val minutes = entries.sumOf { it.minutes }
    if (minutes > 0) parts += "${durationLabel(minutes)} manquées"
    return parts.joinToString(" · ")
}

private fun durationLabel(minutes: Int): String {
    val hours = minutes / 60
    val rest = minutes % 60
    return when {
        hours == 0 -> "$rest min"
        rest == 0 -> "$hours h"
        else -> "$hours h $rest"
    }
}

/** Une ligne : le jour, le type, la durée et l'état de justification. */
@Composable
private fun AttendanceRow(entry: com.pronote.clay.domain.AttendanceEntry) {
    val kind = if (entry.kind == com.pronote.clay.domain.AttendanceKind.ABSENCE) {
        "Absence"
    } else {
        "Retard"
    }
    val duration = durationLabel(entry.minutes)
    val justified = if (entry.justified) "justifiée" else "non justifiée"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            // Une seule annonce vocale par ligne, au lieu de trois fragments.
            .semantics(mergeDescendants = true) {
                contentDescription = "$kind du ${shortDate(entry.date)}, $duration, $justified"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(shortDate(entry.date), style = MaterialTheme.typography.bodyMedium)
            Text(
                buildString {
                    append(kind)
                    if (entry.minutes > 0) append(" · ").append(duration)
                    entry.reason?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ClayPill(
            text = if (entry.justified) "Justifiée" else "À justifier",
            tint = if (entry.justified) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

/** « lun. 30 nov. », ou la date brute si elle n'est pas lisible. */
private fun shortDate(iso: String): String {
    if (iso.isBlank()) return "Date inconnue"
    return try {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso) ?: return iso
        SimpleDateFormat("EEE d MMM", Locale.FRENCH).format(parsed)
    } catch (_: Exception) {
        iso
    }
}

/** Bandeaux hors ligne, synchro partielle et erreur, au-dessus de la moyenne. */
@Composable
private fun HomeStatusBanners(
    snapshot: SchoolSnapshot?,
    online: Boolean,
    offline: Boolean,
    issue: HomeUiState.SyncIssue?,
    error: HomeUiState.Error?,
) {
    if (!online || offline) {
        ClayReveal(index = 1) { OfflineBanner(snapshot) }
        Spacer(Modifier.height(14.dp))
    }
    if (issue != null) {
        ClayReveal(index = 1) {
            Column {
                ClayErrorBox(message = issue.message, hint = issue.hint)
                Spacer(Modifier.height(14.dp))
            }
        }
    }
    if (error != null) {
        ClayReveal(index = 1) {
            Column {
                ClayErrorBox(message = error.message, hint = error.hint)
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

/** Courbe d'évolution — masquée tant qu'il n'y a pas deux points à relier. */
@Composable
private fun HomeHistoryBlock(history: List<HistoryPoint>) {
    if (history.size < 2) return

    var periodDays by remember { mutableStateOf(com.pronote.clay.data.HistoryPeriods.ALL_DAYS) }
    Column {
        ClaySectionTitle("Évolution")
        Spacer(Modifier.height(12.dp))
        HistoryPeriodSelector(
            selectedDays = periodDays,
            onSelect = { periodDays = it },
        )
        Spacer(Modifier.height(10.dp))
        HistoryChart(history, periodDays)
        Spacer(Modifier.height(ClayDimens.SectionGap))
    }
}

/** Liste des matières, avec son état vide. */
@Composable
private fun HomeSubjectsBlock(
    subjects: List<SubjectRow>,
    emptyHint: String? = null,
) {
    Column {
        ClaySectionTitle("Matières")
        Spacer(Modifier.height(12.dp))
        if (subjects.isEmpty()) {
            ClayEmptyState(
                "Aucune matière",
                emptyHint ?: "Les matières apparaîtront après une synchronisation.",
            )
        } else {
            subjects.forEach { row ->
                SubjectRowCard(row)
                Spacer(Modifier.height(ClayDimens.ItemGap))
            }
        }
        Spacer(Modifier.height(ClayDimens.SectionGap))
    }
}

/** Huit dernières notes, avec leur état vide. */
@Composable
private fun HomeGradesBlock(
    snapshot: SchoolSnapshot?,
    emptyHint: String? = null,
    onSelect: (Grade) -> Unit,
) {
    Column {
        ClaySectionTitle("Notes récentes")
        Spacer(Modifier.height(12.dp))
        val grades = snapshot?.grades.orEmpty().sortedByDescending { it.date }
        if (grades.isEmpty()) {
            ClayEmptyState(
                "Aucune note",
                emptyHint ?: "Les nouvelles notes s'afficheront ici.",
            )
        } else {
            grades.take(8).forEach { grade ->
                GradeRowCard(grade, snapshot?.subjects) { onSelect(grade) }
                Spacer(Modifier.height(8.dp))
            }
        }
        Spacer(Modifier.height(ClayDimens.SectionGap))
    }
}

/**
 * « Quelle note me faut-il ? »
 *
 * Le calcul ne sort pas de l'appareil : il part de la moyenne affichée et du
 * poids des notes déjà reçues, puis répond sur le prochain contrôle.
 */
@Composable
private fun HomeTargetBlock(
    average: Double?,
    snapshot: SchoolSnapshot?,
) {
    if (average == null || snapshot == null) return
    val weight = GradeTarget.weightOf(
        snapshot.grades.filter { it.countsTowardAverage }.map { it.coefficient },
    )
    if (weight <= 0.0) return

    TargetCard(average = average, weight = weight)
}

/**
 * Name shown after "Bonjour" — a plain function so the priority rule stays
 * testable outside Compose.
 *
 * The name typed in the settings always wins: it is the only one the user can
 * change, while Pronote's payload varies from one school to the next (missing
 * first name, surname only, "NOM PRÉNOM"…).
 */
fun greetingName(
    configured: String?,
    pronoteFirstName: String?,
    pronoteLastName: String?,
): String? {
    configured?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

    return pronoteFirstName?.trim()
        // "Élève" is Mappers' placeholder when Pronote sends no first name.
        ?.takeIf { it.isNotBlank() && it != "Élève" }
        // A "first name" identical to the surname means Pronote never sent one.
        ?.takeUnless { pronoteLastName != null && it.equals(pronoteLastName.trim(), true) }
}

@Composable
private fun HomeTopBar(
    snapshot: SchoolSnapshot?,
    viewModel: HomeViewModel,
) {
    // En-tête minimal : le nom et l'établissement. La synchro est silencieuse
    // (démarrage, retour du réseau, tâche périodique) : plus de bouton
    // d'actualisation à toucher.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val preferences by viewModel.preferences.collectAsState()
        Column(Modifier.weight(1f)) {
            val greeting = greetingName(
                configured = preferences.displayName,
                pronoteFirstName = snapshot?.student?.firstName,
                pronoteLastName = snapshot?.student?.lastName,
            )
            Text(
                if (greeting != null) "Bonjour $greeting" else "Bonjour",
                fontSize = 32.sp,
                lineHeight = 36.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-1).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    snapshot?.student?.className?.takeIf { it.isNotBlank() },
                    snapshot?.student?.schoolName?.takeIf { it.isNotBlank() },
                ).joinToString(" • ").ifBlank { "Ardoise" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OfflineBanner(snapshot: SchoolSnapshot?) {
    ClaySurface(
        modifier = Modifier.fillMaxWidth(),
        depth = 6.dp,
        shape = RoundedCornerShape(20.dp),
        tint = MaterialTheme.colorScheme.secondary,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.WifiOff,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Hors ligne — données du " + formatTimestamp(snapshot?.fetchedAt),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * The hero card. The average counts up from zero (or from its previous value
 * after a sync) so the first screen never pops in statically.
 */
@Composable
private fun AverageCard(
    average: com.pronote.clay.domain.AverageSummary,
    periodLabel: String? = null,
) {
    val animated = remember { Animatable(0f) }

    LaunchedEffect(average.value) {
        animated.animateTo(
            targetValue = average.value.toFloat(),
            animationSpec = tween(durationMillis = 760, easing = FastOutSlowInEasing),
        )
    }

    // Carte héro : un aplat de couleur primaire porte la moyenne. Pas de
    // dégradé — la couleur du thème suffit, et le mouvement vient du chiffre.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(MaterialTheme.colorScheme.primary),
    ) {
        Row(
            Modifier.padding(ClayDimens.CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (periodLabel != null) {
                    // Sans ce repère, on ne savait pas si le grand nombre parlait
                    // du trimestre courant ou de celui qu'on venait d'ouvrir.
                    Text(
                        periodLabel.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        letterSpacing = 1.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        AverageCalculator.formatAverage(animated.value.toDouble()),
                        fontSize = 56.sp,
                        lineHeight = 58.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-2).sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text(
                        " /20",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                ClayPill(
                    text = if (average.source == com.pronote.clay.domain.AverageSource.OFFICIAL) {
                        "Moyenne officielle"
                    } else {
                        "Moyenne calculée"
                    },
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            val deltaDisplay = average.deltaDisplay
            if (deltaDisplay != null) {
                // The variation badge slides in *after* the average has counted
                // up, so the eye follows the number before reading the change.
                // A MutableTransitionState is required: `AnimatedVisibility`
                // only animates on a state *change*, not on first composition.
                val deltaVisible = remember { MutableTransitionState(false) }
                deltaVisible.targetState = true
                AnimatedVisibility(
                    visibleState = deltaVisible,
                    enter = fadeIn(tween(360, delayMillis = 260)) +
                        expandHorizontally(tween(360, delayMillis = 260)),
                    exit = fadeOut(tween(120)),
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            deltaDisplay,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            "depuis la dernière note",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Trimestres proposés par l'établissement (T1, T2, T3…).
 *
 * Changer de période recharge ses notes, sa moyenne et ses matières ; le
 * trimestre choisi est retenu au prochain lancement.
 */
@Composable
private fun PeriodSelector(
    periods: List<com.pronote.clay.domain.PeriodOption>,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        periods.forEach { period ->
            val selected = period.id == selectedId
            val chipScale by animateFloatAsState(
                targetValue = if (selected) 1.06f else 0.96f,
                animationSpec = tween(220),
                label = "trimestre-chip",
            )
            ClayPill(
                text = period.name,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.scale(chipScale),
                filled = selected,
                onClick = { onSelect(period.id) },
            )
        }
    }
}

/**
 * Period chips for the history chart — 7 jours, 1 mois, 3 mois, Année.
 *
 * The selected chip scales up while the others settle back down, and the whole
 * row animates instead of swapping colour in a single frame.
 */
@Composable
private fun HistoryPeriodSelector(
    selectedDays: Long,
    onSelect: (Long) -> Unit,
) {
    val periods = listOf(
        com.pronote.clay.data.HistoryPeriods.WEEK_DAYS to "7 jours",
        com.pronote.clay.data.HistoryPeriods.MONTH_DAYS to "1 mois",
        com.pronote.clay.data.HistoryPeriods.QUARTER_DAYS to "3 mois",
        com.pronote.clay.data.HistoryPeriods.YEAR_DAYS to "Année",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        periods.forEach { (days, label) ->
            val selected = selectedDays == days ||
                (selectedDays == com.pronote.clay.data.HistoryPeriods.ALL_DAYS && days == com.pronote.clay.data.HistoryPeriods.YEAR_DAYS)
            val chipScale by animateFloatAsState(
                targetValue = if (selected) 1.06f else 0.96f,
                animationSpec = tween(220),
                label = "period-chip",
            )
            ClayPill(
                text = label,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.scale(chipScale),
                filled = selected,
                onClick = { onSelect(days) },
            )
        }
    }
}

/**
 * The evolution curve. The line draws itself left to right and the area under
 * it fades in with the same progress, then the endpoint dot lands — so a period
 * change reads as a movement instead of a jump cut.
 */
@Composable
private fun HistoryChart(history: List<HistoryPoint>, periodDays: Long = com.pronote.clay.data.HistoryPeriods.ALL_DAYS) {
    val accent = MaterialTheme.colorScheme.primary
    ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 8.dp) {
        Column(Modifier.padding(18.dp)) {
            val scoped = remember(history, periodDays) {
                com.pronote.clay.data.PronoteRepositoryImpl.filterHistory(history, periodDays)
            }
            val points = if (scoped.size >= 2) scoped else history

            val draw = remember { Animatable(0f) }
            LaunchedEffect(points) {
                draw.snapTo(0f)
                draw.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = 820, easing = FastOutSlowInEasing),
                )
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            ) {
                val values = points.map { it.value }
                val min = (values.min() - 0.4).coerceAtLeast(0.0).toFloat()
                val max = (values.max() + 0.4).toFloat()
                val span = (max - min).takeIf { it > 0f } ?: 1f
                val progress = draw.value

                fun x(i: Int) = if (points.size == 1) size.width / 2 else i * size.width / (points.size - 1)
                fun y(v: Double) = size.height - ((v.toFloat() - min) / span) * size.height

                // Pas d'aire remplie sous la courbe : une ligne nette suffit.
                val line = Path()
                points.forEachIndexed { i, point ->
                    val px = x(i)
                    val py = y(point.value)
                    if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
                }

                val measure = PathMeasure().apply { setPath(line, false) }
                val drawn = Path()
                measure.getSegment(0f, measure.length * progress, drawn, true)
                drawPath(drawn, color = accent, style = Stroke(width = 7f, cap = StrokeCap.Round))

                if (progress > 0.98f) {
                    drawCircle(
                        accent,
                        radius = 9f,
                        center = Offset(x(points.size - 1), y(points.last().value)),
                    )
                }
            }
        }
    }
}

@Composable
private fun TargetCard(average: Double, weight: Double) {
    val targets = remember { listOf(10.0, 12.0, 14.0, 16.0, 18.0) }
    // La cible par défaut est la plus proche de la moyenne actuelle : on simule
    // rarement un objectif à dix points de là.
    var target by remember(average) {
        mutableStateOf(targets.minByOrNull { kotlin.math.abs(it - average) } ?: 14.0)
    }
    var coefficient by remember { mutableStateOf(1.0) }
    val required = GradeTarget.required(average, weight, coefficient, target)

    ClaySurface(
        modifier = Modifier.fillMaxWidth(),
        depth = 6.dp,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                "Objectif de moyenne",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Quelle note te faut-il au prochain contrôle ?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                targets.forEach { value ->
                    ClayPill(
                        text = AverageCalculator.formatAverage(value),
                        tint = MaterialTheme.colorScheme.primary,
                        filled = value == target,
                        onClick = { target = value },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Coefficient du contrôle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1.0, 2.0, 3.0).forEach { value ->
                    ClayPill(
                        text = "× ${value.toInt()}",
                        tint = MaterialTheme.colorScheme.primary,
                        filled = value == coefficient,
                        onClick = { coefficient = value },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text(
                targetAnswer(required, target, coefficient),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private fun targetAnswer(required: Double?, target: Double, coefficient: Double): String = when {
    required == null -> "Ajoute une note pour que l'app puisse calculer."
    GradeTarget.isAlreadyReached(required) ->
        "Objectif déjà atteint : même un 0 ne te ferait pas passer sous " +
            "${AverageCalculator.formatAverage(target)}."
    !GradeTarget.isReachable(required) ->
        "Il faudrait ${AverageCalculator.formatAverage(required)}/20 : ce n'est plus " +
            "atteignable avec un seul contrôle de coefficient ${coefficient.toInt()}."
    else -> "Il te faut ${AverageCalculator.formatAverage(required)}/20 à ce contrôle."
}

@Composable
private fun SubjectRowCard(row: SubjectRow) {
    val accent = subjectAccent(row.subject.colorIndex)
    ClaySurface(
        modifier = Modifier.fillMaxWidth(),
        depth = 6.dp,
        shape = RoundedCornerShape(22.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .clay(depth = 4.dp, shape = androidx.compose.foundation.shape.CircleShape, tint = accent),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(row.subject.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(
                        row.subject.teacher.takeIf { it.isNotBlank() },
                        "${row.gradeCount} note" + if (row.gradeCount > 1) "s" else "",
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    row.display,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                )
                if (row.deltaDisplay != null) {
                    val up = (row.delta ?: 0.0) >= 0.0
                    Text(
                        row.deltaDisplay,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (up) Color(0xFF4E8C84) else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Une ligne de note, volontairement dépouillée : l'intitulé et la note.
 *
 * Matière, date et coefficient remplissaient la liste sans aider à lire les
 * notes ; ils vivent désormais dans la fiche qui s'ouvre au toucher.
 */
@Composable
private fun GradeRowCard(grade: Grade, subjects: List<Subject>?, onClick: () -> Unit) {
    val subject = subjects?.find { it.id == grade.subjectId }
    val accent = subjectAccent(subject?.colorIndex ?: 0)
    ClaySurface(
        modifier = Modifier.fillMaxWidth(),
        depth = 4.dp,
        shape = RoundedCornerShape(18.dp),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clay(depth = 3.dp, shape = androidx.compose.foundation.shape.CircleShape, tint = accent),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                grade.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                AverageCalculator.formatGrade(grade.value, grade.maxValue),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

/**
 * Fiche d'une note — le pendant du geste « tap » sur la liste.
 *
 * Elle monte du bas avec un ressort légèrement rebondissant, façon feuille
 * iOS, et montre tout ce que la ligne a retiré : matière, professeur, barème,
 * coefficient, date longue, moyenne de la matière et commentaire.
 * Fermeture au toucher du fond, au bouton retour ou au bouton « Fermer ».
 */
@Composable
private fun GradeDetailSheet(
    grade: Grade?,
    subject: Subject?,
    subjectAverage: com.pronote.clay.domain.SubjectAverage?,
    onDismiss: () -> Unit,
) {
    // La note reste affichée le temps de l'animation de sortie.
    var shown by remember { mutableStateOf<Pair<Grade, Subject?>?>(null) }
    LaunchedEffect(grade) { if (grade != null) shown = grade to subject }

    AnimatedVisibility(
        visible = grade != null,
        enter = slideInVertically(
            initialOffsetY = { height -> height },
            // Rebond volontairement marqué : le panneau dépasse sa position
            // puis se stabilise, au lieu de s'arrêter net.
            animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        ) + fadeIn(animationSpec = tween(140)),
        exit = slideOutVertically(
            targetOffsetY = { height -> height },
            animationSpec = tween(180),
        ) + fadeOut(animationSpec = tween(160)),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.34f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            shown?.let { (current, currentSubject) ->
                GradeDetailCard(
                    grade = current,
                    subject = currentSubject,
                    subjectAverage = subjectAverage,
                    onDismiss = onDismiss,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun GradeDetailCard(
    grade: Grade,
    subject: Subject?,
    subjectAverage: com.pronote.clay.domain.SubjectAverage?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val accent = subjectAccent(subject?.colorIndex ?: 0)

    ClaySurface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            // La fiche flotte AU-DESSUS de la barre de navigation : ses boutons
            // ne doivent jamais tomber sous la pilule flottante.
            .padding(horizontal = 10.dp, vertical = 10.dp)
            .padding(bottom = 78.dp),
        depth = 16.dp,
        shape = RoundedCornerShape(30.dp),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 44.dp, height = 5.dp)
                    .clay(
                        depth = 2.dp,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    ),
            )
            Spacer(Modifier.height(16.dp))
            Text(grade.title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clay(depth = 3.dp, shape = androidx.compose.foundation.shape.CircleShape, tint = accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    listOfNotNull(
                        subject?.name ?: "Matière inconnue",
                        subject?.teacher?.takeIf { it.isNotBlank() },
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    AverageCalculator.formatGrade(grade.value, grade.maxValue),
                    fontSize = 44.sp,
                    lineHeight = 46.sp,
                    fontWeight = FontWeight.Black,
                )
                if (!grade.countsTowardAverage) {
                    Spacer(Modifier.width(10.dp))
                    ClayPill(
                        text = "non comptée",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            GradeDetailRow("Date", longDate(grade.date))
            GradeDetailRow("Coefficient", AverageCalculator.trim(grade.coefficient))
            GradeDetailRow("Barème", "sur " + AverageCalculator.trim(grade.maxValue))
            GradeDetailRow(
                "Comptée dans la moyenne",
                if (grade.countsTowardAverage) "oui" else "non",
            )
            if (subjectAverage != null) {
                GradeDetailRow(
                    "Moyenne de la matière",
                    AverageCalculator.formatAverage(subjectAverage.value) + "/20" +
                        if (subjectAverage.source == com.pronote.clay.domain.AverageSource.CALCULATED) {
                            " (calculée)"
                        } else {
                            ""
                        },
                )
            }
            grade.comment?.takeIf { it.isNotBlank() }?.let { comment ->
                Spacer(Modifier.height(14.dp))
                ClaySurface(
                    modifier = Modifier.fillMaxWidth(),
                    depth = 3.dp,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        comment,
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            ClayButton(text = "Fermer", onClick = onDismiss)
        }
    }
}

@Composable
private fun GradeDetailRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun longDate(iso: String): String = try {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
    if (parsed == null) {
        iso
    } else {
        SimpleDateFormat("EEEE d MMMM yyyy", Locale.FRENCH).format(parsed)
    }
} catch (_: Exception) {
    iso
}

private fun formatTimestamp(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0L) return "dernière synchro"
    return SimpleDateFormat("d MMM 'à' HH:mm", Locale.FRENCH).format(Date(epochMs))
}
