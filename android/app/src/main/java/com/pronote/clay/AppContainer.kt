package com.pronote.clay

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.content.pm.PackageInfoCompat
import com.pronote.clay.core.NetworkMonitor
import com.pronote.clay.data.PronoteRepository
import com.pronote.clay.data.PronoteRepositoryImpl
import com.pronote.clay.data.SessionManager
import com.pronote.clay.data.local.LocalDataSource
import com.pronote.clay.data.local.PreferencesStore
import com.pronote.clay.data.remote.PronoteDirectDataSource
import com.pronote.clay.data.remote.PronoteCompatibilityLayer
import com.pronote.clay.data.remote.PronoteDataSource
import com.pronote.clay.data.remote.SchoolDirectory
import com.pronote.clay.data.remote.UpdateChecker
import com.pronote.clay.data.security.SecureCredentialsManager
import com.pronote.clay.domain.CalculateGeneralAverageUseCase
import com.pronote.clay.domain.DeleteLocalDataUseCase
import com.pronote.clay.domain.GetAveragesUseCase
import com.pronote.clay.domain.GetCachedDataUseCase
import com.pronote.clay.domain.GetGradesUseCase
import com.pronote.clay.domain.GetHistoryUseCase
import com.pronote.clay.domain.GetPeriodsUseCase
import com.pronote.clay.domain.GetSelectedPeriodUseCase
import com.pronote.clay.domain.LoadPeriodUseCase
import com.pronote.clay.domain.GetStudentUseCase
import com.pronote.clay.domain.GetSubjectRowsUseCase
import com.pronote.clay.domain.GetSubjectsUseCase
import com.pronote.clay.domain.GetTimetableUseCase
import com.pronote.clay.domain.LoginUseCase
import com.pronote.clay.domain.LogoutUseCase
import com.pronote.clay.domain.RefreshSessionUseCase
import com.pronote.clay.domain.SyncPronoteUseCase
import com.pronote.clay.sync.SyncManager

/**
 * Composition root.
 *
 * Swapping the Pronote connector is a one-line change here: nothing else in the
 * app knows which implementation is behind [PronoteDataSource].
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /** True only for a debuggable build — never for a published APK. */
    private val isDebuggable: Boolean =
        (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * The Pronote connector.
     *
     * [debugConnector] is the seam the screenshot harness uses to drive the real
     * screens without a live Pronote account. It is inert in release builds: a
     * published APK is not debuggable, so it always talks to the establishment's
     * The Pronote connector.
     *
     * [debugConnector] is the seam the screenshot harness uses to drive the real
     * screens without a live Pronote account. It is inert in release builds: a
     * published APK is not debuggable, so it always talks to the establishment's
     * real server.
     *
     * Drapeau posé par le receiver de démo (source set `debug` uniquement) :
     * `adb shell am broadcast -a com.pronote.clay.DEMO`. Un fichier plutôt
     * qu'un champ statique, parce que le conteneur est construit avant que le
     * broadcast n'arrive. Sans ce fichier — et donc dans tout APK publié, non
     * debuggable — rien ne change.
     */
    private val demoModeRequested: Boolean
        get() = isDebuggable && java.io.File(appContext.filesDir, DEMO_MODE_FILE).exists()

    val dataSource: PronoteDataSource =
        if (isDebuggable && (debugConnector != null || demoModeRequested)) {
            debugConnector ?: com.pronote.clay.data.remote.FakePronoteDataSource()
        } else {
            PronoteDirectDataSource()
        }

    val networkMonitor = NetworkMonitor(appContext)

    /**
     * Annuaire des établissements (recherche par ville) : interrogé depuis
     * l'appareil, sans passer par un serveur Ardoise.
     */
    val schoolDirectory = SchoolDirectory(
        // Les dernières recherches survivent à la fermeture : une ville déjà
        // cherchée se rouvre instantanément, même sans réseau.
        cacheFile = java.io.File(appContext.filesDir, "schools-cache.json"),
        online = { networkMonitor.isOnline() },
    )

    /** Diagnostic « ça ne se connecte pas » (une simple lecture de page). */
    val connectionDoctor = com.pronote.clay.data.remote.ConnectionDoctor()

    private val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)

    /** Version installée, affichée dans les réglages à côté de la version publiée. */
    val installedVersionLabel: String =
        "${packageInfo.versionName ?: "?"} (${PackageInfoCompat.getLongVersionCode(packageInfo)})"

    /**
     * La vérification de mise à jour n'existe que dans les builds publiés :
     * un build de développement (tests, captures, démonstration) ne parle
     * jamais au réseau tout seul.
     */
    val updateChecker: UpdateChecker? =
        if (isDebuggable) null
        else UpdateChecker(currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo).toInt())

    val preferences = PreferencesStore(appContext)

    private val local = LocalDataSource(appContext)
    private val vault = SecureCredentialsManager(appContext)
    private val connector = PronoteCompatibilityLayer(dataSource)
    private val sessions = SessionManager(connector, vault)

    val repository: PronoteRepository = PronoteRepositoryImpl(connector, sessions, local) {
        // Chaque nouvelle donnée est répercutée sur les widgets d'accueil.
        com.pronote.clay.widgets.ArdoiseWidgetProvider.updateAllAsync(appContext)
    }

    val syncManager = SyncManager(repository) { networkMonitor.isOnline() }

    // ---- use cases --------------------------------------------------------

    val login = LoginUseCase(repository)
    val logout = LogoutUseCase(repository)
    val getCachedData = GetCachedDataUseCase(repository)
    val syncPronote = SyncPronoteUseCase(repository)
    val refreshSession = RefreshSessionUseCase(repository)
    val deleteLocalData = DeleteLocalDataUseCase(repository)
    val getStudent = GetStudentUseCase()
    val getSubjects = GetSubjectsUseCase()
    val getGrades = GetGradesUseCase()
    val getAverages = GetAveragesUseCase()
    val getSubjectRows = GetSubjectRowsUseCase()
    val getTimetable = GetTimetableUseCase(repository)
    val calculateGeneralAverage = CalculateGeneralAverageUseCase()
    val getPeriods = GetPeriodsUseCase(repository)
    val getSelectedPeriod = GetSelectedPeriodUseCase(repository)
    val loadPeriod = LoadPeriodUseCase(repository)
    val getHistory = GetHistoryUseCase(repository)

    /**
     * The single account on this device. A multi-profile build would replace this
     * with a list of accounts, but the per-account scoping already isolates
     * storage, cache and session per account.
     */
    fun activeAccountId(): String? = activeAccount

    /**
     * Re-rend les widgets d'accueil après un changement de préférence.
     *
     * Le thème choisi change leur habit : sans cet appel, ils gardaient les
     * couleurs de l'ancien réglage jusqu'à la synchronisation suivante.
     */
    fun refreshWidgets() {
        com.pronote.clay.widgets.ArdoiseWidgetProvider.updateAllAsync(appContext)
    }

    fun setActiveAccount(accountId: String?) {
        activeAccount = accountId
        appContext.getSharedPreferences("pronote_accounts", Context.MODE_PRIVATE)
            .edit()
            .putString("active", accountId)
            .apply()
    }

    private var activeAccount: String? =
        appContext.getSharedPreferences("pronote_accounts", Context.MODE_PRIVATE)
            .getString("active", null)

    companion object {
        /** Fichier témoin du mode démonstration, créé par `src/debug`. */
        const val DEMO_MODE_FILE = "demo-mode"

        /**
         * Swappable connector, set by tests only (never by the app). Debug-only:
         * a release build ignores it entirely.
         */
        @Volatile
        var debugConnector: PronoteDataSource? = null
    }
}