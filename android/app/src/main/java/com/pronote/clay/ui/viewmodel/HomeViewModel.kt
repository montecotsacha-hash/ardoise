package com.pronote.clay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pronote.clay.AppContainer
import com.pronote.clay.core.PronoteError
import com.pronote.clay.core.PronoteResult
import com.pronote.clay.core.SchoolSearch
import com.pronote.clay.core.SchoolSuggestion
import com.pronote.clay.data.remote.Diagnosis
import com.pronote.clay.domain.AverageSummary
import com.pronote.clay.domain.Preferences
import com.pronote.clay.domain.SchoolSnapshot
import com.pronote.clay.domain.SubjectRow
import com.pronote.clay.data.remote.UpdateChecker
import com.pronote.clay.data.remote.UpdateStatus
import com.pronote.clay.notifications.LocalNotifications
import com.pronote.clay.notifications.PendingNotification
import com.pronote.clay.sync.SyncManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explicit UI state, mirroring the product spec:
 * Loading | Success | Refreshing | Offline | Error | Unauthenticated
 */
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data object Unauthenticated : HomeUiState

    data class Success(
        val snapshot: SchoolSnapshot,
        val average: AverageSummary?,
        val subjects: List<SubjectRow>,
        val refreshing: Boolean,
    ) : HomeUiState

    data class Offline(
        val snapshot: SchoolSnapshot?,
        val average: AverageSummary?,
        val subjects: List<SubjectRow> = emptyList(),
    ) : HomeUiState

    /**
     * Sync failed but cached data exists: the dashboard stays usable and the
     * problem is shown as a banner. Not an Error state — Error only when
     * there is nothing to show.
     */
    data class SyncIssue(
        val snapshot: SchoolSnapshot,
        val average: AverageSummary?,
        val subjects: List<SubjectRow> = emptyList(),
        val message: String,
        val hint: String? = null,
    ) : HomeUiState

    data class Error(
        val message: String,
        val hint: String?,
        val snapshot: SchoolSnapshot?,
        val subjects: List<SubjectRow> = emptyList(),
    ) : HomeUiState
}

/**
 * Login form draft. It lives in the ViewModel because the login screen is
 * unmounted while the attempt runs: a failed attempt must never wipe what the
 * user typed — only the offending value is cleared.
 */
data class LoginDraft(
    val schoolUrl: String = "",
    val username: String = "",
    val password: String = "",
)

/**
 * État du sélecteur d'établissement (recherche par ville).
 *
 * [searched] distingue « rien encore demandé » de « cherché, aucun résultat » :
 * sans lui, un élève d'une ville sans PRONOTE lirait un écran vide sans savoir
 * si sa recherche a seulement été lancée.
 */
data class SchoolPickerState(
    val query: String = "",
    val searching: Boolean = false,
    val searched: Boolean = false,
    val results: List<SchoolSuggestion> = emptyList(),
    val error: String? = null,
)

/** Transient events the UI must act on (notifications, toasts). */
sealed interface HomeEvent {
    data class Notify(val notification: PendingNotification) : HomeEvent
    data class Message(val text: String) : HomeEvent
}

/**
 * HomeViewModel — holds no business logic: every decision goes through a use
 * case or the SyncManager. It renders cached data first, then refreshes
 * silently in the background.
 */
class HomeViewModel(
    internal val container: AppContainer,
    // Nul dans les builds de développement : la vérification automatique ne
    // tourne que dans l'APK publié. Les tests injectent un factice.
    private val updateChecker: UpdateChecker? = container.updateChecker,
    // La lecture du manifeste est bloquante : toujours hors du fil principal.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    private val _events = MutableStateFlow<HomeEvent?>(null)
    val events: StateFlow<HomeEvent?> = _events.asStateFlow()

    private val _preferences = MutableStateFlow(container.preferences.load())
    val preferences: StateFlow<Preferences> = _preferences.asStateFlow()

    private val _online = MutableStateFlow(container.networkMonitor.isOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _loginDraft = MutableStateFlow(LoginDraft())
    val loginDraft: StateFlow<LoginDraft> = _loginDraft.asStateFlow()

    // ---- annuaire des établissements ---------------------------------------

    /** Établissement choisi dans la liste : son nom s'affiche à la connexion. */
    private val _pickedSchool = MutableStateFlow<SchoolSuggestion?>(null)
    val pickedSchool: StateFlow<SchoolSuggestion?> = _pickedSchool.asStateFlow()

    private val _schoolPicker = MutableStateFlow(SchoolPickerState())
    val schoolPicker: StateFlow<SchoolPickerState> = _schoolPicker.asStateFlow()

    /** Résultat du diagnostic d'adresse, ou null tant qu'on n'a rien demandé. */
    private val _diagnosis = MutableStateFlow<Diagnosis?>(null)
    val diagnosis: StateFlow<Diagnosis?> = _diagnosis.asStateFlow()

    private val _diagnosing = MutableStateFlow(false)
    val diagnosing: StateFlow<Boolean> = _diagnosing.asStateFlow()

    /**
     * Vérifie qu'une adresse donne bien sur un PRONOTE joignable.
     *
     * C'est la réponse à « je ne comprends pas pourquoi ça ne marche pas » :
     * une seule requête, aucun identifiant, et une cause nommée.
     */
    fun diagnoseConnection(url: String) {
        if (_diagnosing.value || url.isBlank()) return
        _diagnosing.value = true
        _diagnosis.value = null
        viewModelScope.launch {
            _diagnosis.value = container.connectionDoctor.check(url)
            _diagnosing.value = false
        }
    }

    fun clearDiagnosis() {
        _diagnosis.value = null
    }

    /**
     * Cherche les établissements proches de la ville saisie.
     *
     * Le nom de la ville part seul vers la Base Adresse Nationale puis vers
     * l'annuaire PRONOTE : aucun identifiant, aucune donnée personnelle.
     */
    fun searchSchools() {
        val query = SchoolSearch.normalizeQuery(_schoolPicker.value.query)
        if (query.length < SchoolSearch.MIN_QUERY_LENGTH || _schoolPicker.value.searching) return
        // Une ville déjà cherchée s'affiche tout de suite, pendant que la
        // requête vérifie s'il y a du nouveau.
        _schoolPicker.value = SchoolPickerState(
            query = query,
            searching = true,
            results = container.schoolDirectory.cached(query),
        )
        viewModelScope.launch {
            val result = container.schoolDirectory.search(query)
            _schoolPicker.value = when (result) {
                is PronoteResult.Success -> SchoolPickerState(
                    query = query,
                    searched = true,
                    results = result.value,
                    error = if (result.value.isEmpty()) {
                        "Aucun établissement PRONOTE trouvé autour de « $query »."
                    } else {
                        null
                    },
                )
                is PronoteResult.Failure -> SchoolPickerState(
                    query = query,
                    searched = true,
                    error = result.error.userMessage,
                )
            }
        }
    }

    fun pickSchool(school: SchoolSuggestion) {
        _pickedSchool.value = school
        _loginDraft.value = _loginDraft.value.copy(schoolUrl = school.url)
    }

    // ---- périodes (trimestres) --------------------------------------------

    private val _periods = MutableStateFlow<List<com.pronote.clay.domain.PeriodOption>>(emptyList())
    val periods: StateFlow<List<com.pronote.clay.domain.PeriodOption>> = _periods.asStateFlow()

    private val _selectedPeriod = MutableStateFlow<String?>(null)
    val selectedPeriod: StateFlow<String?> = _selectedPeriod.asStateFlow()

    /**
     * Historique de l'année, tous trimestres confondus. La courbe ne doit pas
     * disparaître quand le trimestre en cours n'a qu'une note.
     */
    private val _history = MutableStateFlow<List<com.pronote.clay.domain.HistoryPoint>>(emptyList())
    val history: StateFlow<List<com.pronote.clay.domain.HistoryPoint>> = _history.asStateFlow()

    private fun refreshPeriods(accountId: String?) {
        if (accountId == null) {
            _periods.value = emptyList()
            _selectedPeriod.value = null
            _history.value = emptyList()
            return
        }
        _periods.value = container.getPeriods.execute(accountId)
        _selectedPeriod.value = container.getSelectedPeriod.execute(accountId)
        _history.value = container.getHistory.execute(
            accountId,
            com.pronote.clay.data.HistoryPeriods.ALL_DAYS,
        )
    }

    /**
     * Bascule sur un trimestre : notes, moyenne et matières de cette période.
     * Le trimestre choisi est mémorisé pour les prochains lancements.
     */
    fun selectPeriod(periodId: String) {
        val accountId = container.activeAccountId() ?: return
        if (_selectedPeriod.value == periodId) return
        _selectedPeriod.value = periodId
        viewModelScope.launch {
            val wasSuccess = _state.value as? HomeUiState.Success
            _state.value = if (wasSuccess != null) wasSuccess.copy(refreshing = true) else HomeUiState.Loading
            when (val result = container.loadPeriod.execute(accountId, periodId)) {
                is PronoteResult.Success -> {
                    _state.value = buildSuccess(result.value, refreshing = false)
                    refreshPeriods(accountId)
                }
                is PronoteResult.Failure -> {
                    // On garde l'écran utilisable et on le dit ; le cache du
                    // trimestre reste affiché s'il existe.
                    val cached = container.getCachedData.execute(accountId)
                    _state.value = if (cached != null) {
                        HomeUiState.SyncIssue(
                            snapshot = cached,
                            average = container.calculateGeneralAverage.execute(cached),
                            subjects = container.getSubjectRows.execute(cached),
                            message = result.error.userMessage,
                            hint = result.error.hint,
                        )
                    } else {
                        HomeUiState.Error(
                            message = result.error.userMessage,
                            hint = result.error.hint,
                            snapshot = null,
                        )
                    }
                }
            }
        }
    }

    fun updateSchoolQuery(value: String) {
        _schoolPicker.value = _schoolPicker.value.copy(query = value)
    }

    fun updateLoginSchoolUrl(value: String) {
        _loginDraft.value = _loginDraft.value.copy(schoolUrl = value)
        // Une adresse tapée à la main remplace l'établissement choisi : garder
        // son nom affiché ferait croire que la connexion vise cet établissement.
        if (_pickedSchool.value?.url != value.trim()) _pickedSchool.value = null
    }

    fun updateLoginUsername(value: String) {
        _loginDraft.value = _loginDraft.value.copy(username = value)
    }

    fun updateLoginPassword(value: String) {
        _loginDraft.value = _loginDraft.value.copy(password = value)
    }

    private var sessionValid = false

    /** Human label of the connector strategy, shown in the settings sheet. */
    val connectorLabel: String get() = container.dataSource.strategy

    init {
        viewModelScope.launch {
            container.networkMonitor.online.collect { isOnline ->
                _online.value = isOnline
                applyCurrentState(isOnline)
            }
        }
        loadFromCacheThenRefresh()
        // Vérification silencieuse au lancement : rien ne s'affiche tant
        // qu'aucune version plus récente n'est publiée.
        updateChecker?.let { checkWith(it) }
    }

    /** Compare la version installée à celle publiée sur le site de distribution. */
    fun checkForUpdate() {
        val checker = updateChecker
        if (checker == null) {
            _updateStatus.value = UpdateStatus.Unavailable
            return
        }
        checkWith(checker)
    }

    private fun checkWith(checker: UpdateChecker) {
        if (_updateStatus.value is UpdateStatus.Checking) return
        _updateStatus.value = UpdateStatus.Checking
        viewModelScope.launch {
            _updateStatus.value = withContext(ioDispatcher) { checker.check() }
        }
    }

    /** « Plus tard » : la proposition n'est plus imposée, mais reste en réglages. */
    fun dismissUpdate() {
        val current = _updateStatus.value
        if (current is UpdateStatus.Available) {
            _updateStatus.value = current.copy(promptVisible = false)
        }
    }

    /** Step 1: show local data instantly. Step 2: refresh silently. */
    private fun loadFromCacheThenRefresh() {
        val accountId = container.activeAccountId()
        if (accountId == null) {
            _state.value = HomeUiState.Unauthenticated
            return
        }

        val cached = container.getCachedData.execute(accountId)
        if (cached == null) {
            _state.value = HomeUiState.Unauthenticated
            return
        }

        refreshPeriods(accountId)
        _state.value = buildSuccess(cached, refreshing = true)

        viewModelScope.launch {
            // Session restore is bounded so the UI never blocks on the network.
            val restored = container.refreshSession.execute(accountId)
            sessionValid = restored != null
            if (restored != null) {
                sync(force = false)
            } else {
                applyCurrentState(container.networkMonitor.isOnline())
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { sync(force = true) }
    }

    /**
     * Repli une seule fois vers la période précédente quand celle du serveur
     * est vide. Aucune boucle : si elle est vide aussi, l'écran garde le
     * sélecteur et un message explicite.
     */
    private suspend fun fallbackToPreviousPeriod(accountId: String) {
        val periods = container.getPeriods.execute(accountId)
        if (periods.size < 2) return
        val current = container.getSelectedPeriod.execute(accountId)
        val index = periods.indexOfFirst { it.id == current }
        val previous = periods.getOrNull(index - 1) ?: return
        when (val result = container.loadPeriod.execute(accountId, previous.id)) {
            is PronoteResult.Success -> if (result.value.grades.isNotEmpty()) {
                _state.value = buildSuccess(result.value, refreshing = false)
                refreshPeriods(accountId)
            }
            is PronoteResult.Failure -> Unit
        }
    }

    fun markSessionInvalid() {
        sessionValid = false
        applyCurrentState(container.networkMonitor.isOnline())
    }

    private suspend fun sync(force: Boolean) {
        val accountId = container.activeAccountId() ?: return
        setRefreshing(true)

        val result = container.syncManager.sync(accountId, force = force)

        when {
            result.ok && result.snapshot != null -> {
                _state.value = buildSuccess(result.snapshot, refreshing = false)
                refreshPeriods(accountId)
                emitNotifications(result)
            }
            result.skipped == SyncManager.SkipReason.OFFLINE -> {
                applyCurrentState(isOnline = false)
            }
            result.error != null -> {
                val cached = container.getCachedData.execute(accountId)
                if (cached != null) {
                    // With local data available, a failed background sync is
                    // never a login failure: keep the dashboard usable and
                    // report the problem as a banner the next retry can clear.
                    _state.value = HomeUiState.SyncIssue(
                        snapshot = cached,
                        average = container.calculateGeneralAverage.execute(cached),
                        subjects = container.getSubjectRows.execute(cached),
                        message = result.error,
                        hint = result.errorHint,
                    )
                } else {
                    _state.value = HomeUiState.Error(
                        message = result.error,
                        hint = result.errorHint ?: PronoteError.NetworkUnavailable.hint,
                        snapshot = null,
                        subjects = emptyList(),
                    )
                }
            }
            else -> setRefreshing(false)
        }
    }

    private fun emitNotifications(result: SyncManager.SyncResult) {
        val prefs = _preferences.value
        val snapshot = result.snapshot ?: return

        if (prefs.notifyNewGrades && result.newGrades.isNotEmpty()) {
            val grade = result.newGrades.first()
            val subject = snapshot.subjects.find { it.id == grade.subjectId }
            _events.value = HomeEvent.Notify(
                PendingNotification(
                    title = "Nouvelle note",
                    body = LocalNotifications.newGradeNotification(grade, subject),
                ),
            )
            return
        }

        val previous = result.previousAverage
        val next = result.nextAverage
        if (prefs.notifyAverageChange && previous != null && next != null &&
            kotlin.math.abs(next - previous) >= 0.05
        ) {
            _events.value = HomeEvent.Notify(
                PendingNotification(
                    title = "Ta moyenne a changé",
                    body = LocalNotifications.averageChangeNotification(next, previous),
                ),
            )
        }
    }

    fun consumeEvent() {
        _events.value = null
    }

    private fun setRefreshing(refreshing: Boolean) {
        val current = _state.value
        if (current is HomeUiState.Success) {
            _state.value = current.copy(refreshing = refreshing)
        }
    }

    private fun applyCurrentState(isOnline: Boolean) {
        val accountId = container.activeAccountId()
        val snapshot = accountId?.let { container.getCachedData.execute(it) }

        _state.value = when {
            snapshot == null -> HomeUiState.Unauthenticated
            !isOnline -> HomeUiState.Offline(
                snapshot = snapshot,
                average = container.calculateGeneralAverage.execute(snapshot),
                subjects = container.getSubjectRows.execute(snapshot),
            )
            else -> buildSuccess(snapshot, refreshing = false)
        }
    }

    private fun buildSuccess(snapshot: SchoolSnapshot, refreshing: Boolean): HomeUiState.Success =
        HomeUiState.Success(
            snapshot = snapshot,
            average = container.calculateGeneralAverage.execute(snapshot),
            subjects = container.getSubjectRows.execute(snapshot),
            refreshing = refreshing,
        )

    // ---- actions ----------------------------------------------------------

    fun login(
        schoolUrl: String,
        username: String,
        password: String,
        onSuccess: () -> Unit,
    ) {
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            val accountId = accountIdFor(username, schoolUrl)

            when (val result = container.login.execute(accountId, schoolUrl, username, password)) {
                is PronoteResult.Success -> {
                    container.setActiveAccount(accountId)
                    container.syncManager.reset()
                    sessionValid = true
                    val snapshot = container.getCachedData.execute(accountId)
                    refreshPeriods(accountId)
                    _state.value = if (snapshot != null) {
                        buildSuccess(snapshot, refreshing = false)
                    } else {
                        HomeUiState.Unauthenticated
                    }
                    onSuccess()
                    // Pronote ouvre souvent sur le trimestre qui vient de
                    // commencer : il est vide alors que le précédent porte
                    // toutes les notes. On bascule une fois sur la période
                    // précédente plutôt que d'afficher un tableau vide.
                    if (snapshot != null && snapshot.grades.isEmpty()) {
                        fallbackToPreviousPeriod(accountId)
                    }
                }
                is PronoteResult.Failure -> {
                    // Only clear the value that caused the failure; the other
                    // entries (address, identifiants) stay filled in.
                    _loginDraft.value = when (result.error) {
                        is PronoteError.InvalidPronoteUrl ->
                            _loginDraft.value.copy(schoolUrl = "")
                        is PronoteError.InvalidCredentials ->
                            _loginDraft.value.copy(password = "")
                        else -> _loginDraft.value
                    }
                    _state.value = HomeUiState.Error(
                        message = result.error.userMessage,
                        hint = result.error.hint,
                        snapshot = null,
                    )
                }
            }
        }
    }

    /** Stable local account id, derived from the establishment and login. */
    private fun accountIdFor(username: String, schoolUrl: String): String =
        "acct-" + Integer.toHexString("$schoolUrl|$username".hashCode())

    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            container.activeAccountId()?.let { container.logout.execute(it) }
            container.setActiveAccount(null)
            sessionValid = false
            refreshPeriods(null)
            _pickedSchool.value = null
            _schoolPicker.value = SchoolPickerState()
            _state.value = HomeUiState.Unauthenticated
            onDone()
        }
    }

    fun deleteLocalData(onDone: () -> Unit) {
        viewModelScope.launch {
            container.activeAccountId()?.let { container.deleteLocalData.execute(it) }
            container.setActiveAccount(null)
            container.preferences.clear()
            // Les widgets portent des données personnelles : ils doivent
            // repasser à l'état vide, sinon la moyenne restait affichée sur
            // l'écran d'accueil après la suppression.
            container.refreshWidgets()
            _preferences.value = container.preferences.load()
            sessionValid = false
            refreshPeriods(null)
            _state.value = HomeUiState.Unauthenticated
            onDone()
        }
    }

    fun updatePreferences(preferences: Preferences) {
        container.preferences.save(preferences)
        _preferences.value = preferences
        // Le thème et les couleurs font aussi partie des widgets.
        container.refreshWidgets()
    }

    fun clearSession() {
        viewModelScope.launch {
            container.activeAccountId()?.let { container.logout.execute(it) }
            sessionValid = false
        }
    }
}
