package com.pronote.clay.sync

import com.pronote.clay.core.Logger
import com.pronote.clay.core.PronoteError
import com.pronote.clay.core.PronoteResult
import com.pronote.clay.data.PronoteRepository
import com.pronote.clay.domain.Grade
import com.pronote.clay.domain.SchoolSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * SyncManager — the WorkManager equivalent.
 *
 * Guarantees:
 *  - never two concurrent syncs (mutex, mirroring a unique WorkManager job)
 *  - never sync when offline
 *  - skip when the last sync is recent, unless the caller forces it
 *  - detect new grades and average changes after every successful sync
 *  - only announce grades that are actually recent (see [detectNewGrades])
 *  - never surface a session-expired error when a silent reconnect recovers it
 */
class SyncManager(
    private val repository: PronoteRepository,
    private val isOnline: () -> Boolean,
) {

    data class SyncResult(
        val ok: Boolean,
        val snapshot: SchoolSnapshot? = null,
        val newGrades: List<Grade> = emptyList(),
        val previousAverage: Double? = null,
        val nextAverage: Double? = null,
        val skipped: SkipReason? = null,
        val error: String? = null,
        val errorHint: String? = null,
    )

    enum class SkipReason { OFFLINE, RECENT, BUSY }

    private val mutex = Mutex()
    private var lastSyncAt = 0L

    /** Backoff after a hard auth failure so background retries stay rare. */
    private var authBlockedUntil = 0L

    /** Erreur qui a posé le blocage, réaffichée telle quelle aux synchros muettes. */
    private var authBlockedError: PronoteError = PronoteError.SessionExpired

    val isSyncing: Boolean get() = mutex.isLocked

    /**
     * Run a sync. `force` bypasses the "recent sync" guard (the "Actualiser"
     * button), but never bypasses the offline check or the mutex.
     */
    suspend fun sync(accountId: String, force: Boolean = false): SyncResult {
        if (!isOnline()) {
            Logger.info("Sync skipped: offline")
            return SyncResult(ok = false, skipped = SkipReason.OFFLINE)
        }

        if (mutex.isLocked) {
            Logger.info("Sync skipped: already running")
            return SyncResult(ok = false, skipped = SkipReason.BUSY)
        }

        if (!force && System.currentTimeMillis() - lastSyncAt < MIN_SYNC_INTERVAL_MS) {
            Logger.info("Sync skipped: recent")
            return SyncResult(ok = false, skipped = SkipReason.RECENT)
        }

        return mutex.withLock { run(accountId, force) }
    }

    private suspend fun run(accountId: String, force: Boolean): SyncResult {
        val now = System.currentTimeMillis()
        val before = repository.getCachedSnapshot(accountId)
        val previousAverage = before?.generalAverage?.value

        // Credentials genuinely rejected (message shown to the user): don't
        // hammer the server from the WorkManager retries — wait out a backoff
        // that only a manual refresh (force) can cut through.
        if (now < authBlockedUntil && !force) {
            return SyncResult(
                ok = false,
                error = authBlockedError.userMessage,
                errorHint = authBlockedError.hint,
            )
        }

        // Le trimestre affiché, pas celui du serveur : sinon une note ajoutée
        // après coup au T1 ne rafraîchissait que le T2 (trimestre courant),
        // et la moyenne du T1 ne bougeait jamais.
        val periodId = repository.selectedPeriodId(accountId)

        Logger.info("Sync started")
        var result = repository.sync(accountId, periodId)

        // Une session Pronote mourante se rétablit toute seule : on retente une
        // fois après une reconnexion silencieuse, et on n'alerte l'utilisateur
        // que si le problème persiste. Une seule tentative — jamais de boucle.
        // Un jeton refusé (InvalidCredentials) est traité comme une session
        // expirée : le coffre sait refabriquer la session avec le mot de passe,
        // donc « identifiants incorrects » ne doit pas s'afficher pour ça.
        // La session ne doit JAMAIS mourir : chaque refus passager (jeton
        // invalidé par un autre appareil, purge côté serveur, identifiants
        // refusés sur un jeton périmé) déclenche une reconnexion silencieuse
        // puis une nouvelle collecte, plusieurs fois de suite. On ne renonce
        // qu'après plusieurs essais, et même alors rien n'est effacé : le
        // mot de passe reste dans le coffre pour la synchro suivante.
        var attempts = 0
        while (attempts < SILENT_RECONNECTS) {
            val failure = result as? PronoteResult.Failure ?: break
            if (!isRecoverable(failure.error)) break
            if (repository.refreshSession(accountId) == null) break
            Logger.info("Session expirée : reconnexion puis nouvelle tentative")
            delay(RECONNECT_PAUSE_MS * (attempts + 1))
            result = repository.sync(accountId, periodId)
            attempts++
        }

        return when (result) {
            is PronoteResult.Failure -> {
                Logger.warn("Sync failed")
                when {
                    result.error is PronoteError.InvalidCredentials ||
                        result.error is PronoteError.CaptchaRequired -> {
                        authBlockedError = result.error
                        authBlockedUntil = System.currentTimeMillis() + AUTH_BACKOFF_MS
                    }
                    // Pronote limite les tentatives : chaque nouvelle synchro
                    // automatique enverrait un login de plus et prolongerait
                    // le blocage. On laisse passer du temps, sans rien envoyer.
                    result.error is PronoteError.TooManyAttempts -> {
                        authBlockedError = result.error
                        authBlockedUntil = System.currentTimeMillis() + THROTTLE_BACKOFF_MS
                    }
                }
                SyncResult(
                    ok = false,
                    previousAverage = previousAverage,
                    nextAverage = previousAverage,
                    error = result.error.userMessage,
                    errorHint = result.error.hint,
                )
            }
            is PronoteResult.Success -> {
                lastSyncAt = System.currentTimeMillis()
                SyncResult(
                    ok = true,
                    snapshot = result.value,
                    newGrades = detectNewGrades(before, result.value),
                    previousAverage = previousAverage,
                    nextAverage = result.value.generalAverage?.value,
                )
            }
        }
    }

    /** Refus qu'une reconnexion silencieuse peut corriger. */
    private fun isRecoverable(error: PronoteError): Boolean =
        error is PronoteError.SessionExpired || error is PronoteError.InvalidCredentials

    /** Called when a session is replaced so the next sync is never skipped. */
    fun reset() {
        lastSyncAt = 0L
        authBlockedUntil = 0L
        authBlockedError = PronoteError.SessionExpired
    }

    companion object {
        /** Unique WorkManager work name. */
        const val PERIODIC_SYNC_WORK_NAME = "pronote_periodic_sync"

        /** One-shot pass enqueued at every app start (same worker). */
        const val STARTUP_SYNC_WORK_NAME = "pronote_startup_sync"

        /** Minimum delay between two automatic syncs. */
        const val MIN_SYNC_INTERVAL_MS = 5 * 60 * 1000L

        /** Backoff applied after a hard auth failure (bad creds, captcha). */
        const val AUTH_BACKOFF_MS = 15 * 60 * 1000L

        /** Silence imposé quand Pronote suspend temporairement les connexions. */
        const val THROTTLE_BACKOFF_MS = 30 * 60 * 1000L

        /** Reconnexions silencieuses tentées avant de rendre la main. */
        const val SILENT_RECONNECTS = 3

        /** Pause qui grandit entre deux reconnexions (anti-rafale). */
        const val RECONNECT_PAUSE_MS = 400L

        private val AUTH_BACKOFF = PronoteError.SessionExpired

        /** A grade is announced only when it is at most this many days old. */
        const val MAX_NOTIFY_AGE_DAYS = 14L

        /** Below that many "new" grades a wholesale id change is not a baseline. */
        private const val BASELINE_MIN_GRADES = 5

        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /**
         * Compare the previous grade list with the new one, keeping only the
         * grades worth announcing:
         *  - a wholesale id/period change (app update, new trimester) is a new
         *    baseline, not a wave of new grades;
         *  - a grade older than [MAX_NOTIFY_AGE_DAYS] is history, not news.
         * The result is sorted so the most recent grade comes first.
         */
        fun detectNewGrades(before: SchoolSnapshot?, after: SchoolSnapshot): List<Grade> {
            if (before == null) return emptyList()
            val known = before.grades.map { it.id }.toSet()
            val fresh = after.grades.filter { it.id !in known }
            if (fresh.isEmpty()) return emptyList()
            // A large majority of the snapshot suddenly looks new: the id scheme
            // or the period changed, so treat it as a fresh baseline instead of
            // waking the user up for grades they have already seen.
            if (fresh.size >= BASELINE_MIN_GRADES && fresh.size > after.grades.size / 2) return emptyList()
            return fresh
                .filter { isRecentEnoughToNotify(it.date) }
                .sortedByDescending { it.date }
        }

        /**
         * A grade dated more than [MAX_NOTIFY_AGE_DAYS] ago is never announced:
         * a late correction must not look like breaking news. An unparsable date
         * is treated the same way — silence beats a wrong alert.
         */
        private fun isRecentEnoughToNotify(isoDate: String): Boolean {
            val millis = try {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)?.time
            } catch (_: Exception) {
                null
            } ?: return false
            return millis >= System.currentTimeMillis() - MAX_NOTIFY_AGE_DAYS * DAY_MS
        }
    }
}
