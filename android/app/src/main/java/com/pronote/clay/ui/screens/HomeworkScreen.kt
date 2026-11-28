package com.pronote.clay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pronote.clay.domain.Homework
import com.pronote.clay.ui.components.ClayEmptyState
import com.pronote.clay.ui.components.ClayErrorBox
import com.pronote.clay.ui.components.ClayLoading
import com.pronote.clay.ui.components.ClayPill
import com.pronote.clay.ui.components.ClayReveal
import com.pronote.clay.ui.components.ClaySecondaryButton
import com.pronote.clay.ui.components.ClaySurface
import com.pronote.clay.ui.components.clay
import com.pronote.clay.ui.theme.ClayDimens
import com.pronote.clay.ui.viewmodel.HomeworkUiState
import com.pronote.clay.ui.viewmodel.HomeworkViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * HomeworkScreen — le cahier de textes, groupé par jour de remise.
 *
 * Une carte par devoir, la matière en pastille, la consigne en clair (le HTML
 * de Pronote est déjà converti côté données) et les pièces jointes nommées. Le
 * travail est en lecture seule : il est donné par le professeur, pas par
 * l'élève.
 */
@Composable
fun HomeworkScreen(
    viewModel: HomeworkViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(bottom = 88.dp),
    ) {
        HomeworkTopBar(onBack = onBack, onRefresh = viewModel::reload, refreshing = state.refreshing)

        when {
            state.loading -> ClayLoading("Chargement du travail…")

            state.message != null && state.empty -> Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ClayDimens.ScreenPadding, vertical = 12.dp),
            ) {
                ClayErrorBox(state.message.orEmpty(), state.hint)
                Spacer(Modifier.height(14.dp))
                ClaySecondaryButton("Réessayer", onClick = viewModel::reload)
            }

            state.empty -> Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ClayDimens.ScreenPadding, vertical = 12.dp),
            ) {
                ClayEmptyState(
                    "Rien à faire",
                    "Le travail donné par tes professeurs pour les deux prochaines " +
                        "semaines apparaîtra ici.",
                )
            }

            else -> HomeworkList(state)
        }
    }
}

@Composable
private fun HomeworkTopBar(onBack: () -> Unit, onRefresh: () -> Unit, refreshing: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour")
        }
        Text(
            "Travail",
            fontSize = 28.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = (-0.8).sp,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = if (refreshing) {
                    "Actualisation en cours"
                } else {
                    "Actualiser le travail"
                },
            )
        }
    }
}

@Composable
private fun HomeworkList(state: HomeworkUiState) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = ClayDimens.ScreenPadding,
            end = ClayDimens.ScreenPadding,
            top = 6.dp,
            bottom = 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.offline) {
            item(key = "offline") {
                Text(
                    "Hors-ligne : voici le travail mémorisé lors de la dernière connexion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }

        state.days.forEach { day ->
            item(key = "day-${day.dateIso}") {
                DayHeader(day.dateIso, day.items.size)
            }
            items(day.items, key = { it.id }) { homework ->
                HomeworkCard(homework)
            }
        }
    }
}

/** Le jour de remise, dit en français courant : « Aujourd'hui », « Demain », puis la date. */
@Composable
private fun DayHeader(dateIso: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            dayLabel(dateIso).uppercase(Locale.FRENCH),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (count == 1) "1 devoir" else "$count devoirs",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HomeworkCard(homework: Homework) {
    ClayReveal {
        ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 6.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClayPill(
                        text = homework.subjectName.ifBlank { "Matière" },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (homework.done) {
                        Spacer(Modifier.size(8.dp))
                        ClayPill(text = "Fait")
                    }
                }
                if (homework.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        homework.description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (homework.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.AttachFile,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            homework.attachments.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun dayLabel(iso: String): String {
    if (iso.isBlank()) return "Sans date"
    val date = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    val today = LocalDate.now()
    return when (date) {
        today -> "Aujourd'hui"
        today.plusDays(1) -> "Demain"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH))
    }
}
