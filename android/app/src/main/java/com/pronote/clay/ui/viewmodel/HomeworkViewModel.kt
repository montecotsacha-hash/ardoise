package com.pronote.clay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pronote.clay.AppContainer
import com.pronote.clay.core.PronoteResult
import com.pronote.clay.domain.Homework
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Le travail d'un jour, déjà trié par matière. */
data class HomeworkDay(val dateIso: String, val items: List<Homework>)

/**
 * État du cahier de textes.
 *
 * [loading] est vrai seulement quand il n'y a rien à montrer : une liste déjà
 * mémorisée s'affiche pendant que [refreshing] tourne, sans clignotement.
 */
data class HomeworkUiState(
    val days: List<HomeworkDay> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val offline: Boolean = false,
    val message: String? = null,
    val hint: String? = null,
) {
    val empty: Boolean get() = days.isEmpty()
}

/**
 * HomeworkViewModel — cahier de textes de la quinzaine à venir.
 *
 * La fenêtre est fixe (hier à quinze jours) : c'est ce qu'un élève regarde en
 * ouvrant l'app le soir, et cela garde une seule requête par ouverture.
 */
class HomeworkViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(HomeworkUiState())
    val state: StateFlow<HomeworkUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun reload() = load()

    private fun load() {
        val accountId = container.activeAccountId()
        if (accountId == null) {
            _state.value = HomeworkUiState(loading = false)
            return
        }

        val cached = container.getHomework.cached(accountId)
        _state.value = HomeworkUiState(
            days = group(cached),
            loading = cached.isEmpty(),
            refreshing = true,
        )

        viewModelScope.launch {
            when (val result = container.getHomework.execute(accountId, fromIso(), toIso())) {
                is PronoteResult.Success -> _state.value = HomeworkUiState(
                    days = group(result.value),
                    loading = false,
                    offline = !container.networkMonitor.isOnline(),
                )

                is PronoteResult.Failure -> {
                    val fallback = container.getHomework.cached(accountId)
                    _state.value = if (fallback.isEmpty()) {
                        HomeworkUiState(
                            loading = false,
                            message = result.error.userMessage,
                            hint = result.error.hint,
                        )
                    } else {
                        // Hors-ligne : les devoirs déjà vus restent lisibles.
                        HomeworkUiState(days = group(fallback), loading = false, offline = true)
                    }
                }
            }
        }
    }

    /** Regroupe par jour de remise ; les devoirs sans date ferment la liste. */
    private fun group(items: List<Homework>): List<HomeworkDay> =
        items.groupBy { it.dueDate }
            .map { (date, day) -> HomeworkDay(date, day.sortedBy { it.subjectName }) }
            .sortedWith(compareBy({ it.dateIso.isEmpty() }, { it.dateIso }))

    private fun fromIso(): String = LocalDate.now().minusDays(1).toString()

    private fun toIso(): String = LocalDate.now().plusDays(14).toString()
}
