package com.pronote.clay.sync

import com.pronote.clay.core.PronoteError
import com.pronote.clay.core.PronoteResult
import com.pronote.clay.data.PronoteRepository
import com.pronote.clay.domain.Grade
import com.pronote.clay.domain.PronoteSession
import com.pronote.clay.domain.SchoolSnapshot
import com.pronote.clay.domain.Student
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * JVM unit tests for the sync guards and the error propagation the UI relies on.
 */
class SyncManagerTest {

    private val student = Student("s1", "Alex", "Martin", "1re G2", "Lycée Jean Moulin")

    private fun snapshot(vararg grades: Grade) = SchoolSnapshot(
        student = student,
        subjects = emptyList(),
        grades = grades.toList(),
        subjectAverages = emptyList(),
        generalAverage = null,
        history = emptyList(),
        fetchedAt = 0L,
    )

    /** A date [days] ago, ISO formatted like Pronote grades (yyyy-MM-dd). */
    private fun isoDaysAgo(days: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(System.currentTimeMillis() - days * 86_400_000L))

    private fun grade(id: String, date: String = isoDaysAgo(0)) = Grade(
        id = id,
        subjectId = "maths",
        title = "Devoir",
        value = 15.0,
        maxValue = 20.0,
        coefficient = 1.0,
        date = date,
    )

    private class FakeRepository(
        private var current: SchoolSnapshot?,
        private val failure: PronoteError? = null,
    ) : PronoteRepository {
        var syncCount = 0
        var restoreCount = 0

        /** Période que le dépôt retrouve comme sélectionnée par l'utilisateur. */
        var selectedPeriod: String? = null

        /** Période réellement demandée au connecteur au dernier sync. */
        var lastSyncPeriod: String? = null

        /** How many leading syncs fail with [failure] before one succeeds. */
        var failuresBeforeSuccess = if (failure != null) Int.MAX_VALUE else 0

        /** Session handed back by refreshSession — null means "beyond repair". */
        var restoredSession: PronoteSession? = null

        override suspend fun login(
            accountId: String,
            schoolUrl: String,
            username: String,
            password: String,
        ): PronoteResult<PronoteSession> = PronoteResult.Failure(PronoteError.InvalidCredentials)

        override suspend fun refreshSession(accountId: String): PronoteSession? {
            restoreCount++
            return restoredSession
        }

        override fun getCachedSnapshot(accountId: String): SchoolSnapshot? = current

        override fun availablePeriods(accountId: String) = emptyList<com.pronote.clay.domain.PeriodOption>()

        override fun selectedPeriodId(accountId: String): String? = selectedPeriod

        override suspend fun sync(accountId: String, periodId: String?): PronoteResult<SchoolSnapshot> {
            syncCount++
            lastSyncPeriod = periodId
            if (failure != null && failuresBeforeSuccess > 0) {
                failuresBeforeSuccess--
                return PronoteResult.Failure(failure)
            }
            return PronoteResult.Success(current!!)
        }

        override suspend fun logout(accountId: String) = Unit

        override suspend fun deleteLocalData(accountId: String) = Unit

        override fun getHistory(accountId: String, days: Long) = emptyList<com.pronote.clay.domain.HistoryPoint>()

        override suspend fun getTimetable(
            accountId: String,
            weekStartIso: String,
        ): PronoteResult<List<com.pronote.clay.domain.TimetableLesson>> = PronoteResult.Success(emptyList())

        override suspend fun getHomework(
            accountId: String,
            fromIso: String,
            toIso: String,
        ): PronoteResult<List<com.pronote.clay.domain.Homework>> =
            PronoteResult.Success(emptyList())

        override suspend fun getAttendance(
            accountId: String,
        ): PronoteResult<List<com.pronote.clay.domain.AttendanceEntry>> =
            PronoteResult.Success(emptyList())

        override fun cachedHomework(accountId: String) =
            emptyList<com.pronote.clay.domain.Homework>()

        override fun cachedAttendance(accountId: String) =
            emptyList<com.pronote.clay.domain.AttendanceEntry>()

        override fun cachedWeek(accountId: String) =
            emptyList<com.pronote.clay.domain.TimetableLesson>()
    }

    private fun restorableSession() = PronoteSession(
        token = "mobile-token",
        expiresAt = Long.MAX_VALUE,
        schoolUrl = "https://demo.index-education.net/pronote",
        schoolName = "Lycée Jean Moulin",
        connected = true,
    )

    @Test
    fun `carries the real error hint to the UI`() = runBlocking {
        val manager = SyncManager(
            FakeRepository(null, PronoteError.SessionExpired),
        ) { true }

        val result = manager.sync("acct")

        assertFalse(result.ok)
        assertEquals("Ta session Pronote a expiré.", result.error)
        // The UI shows this hint; it used to be hardcoded to the network message.
        assertEquals(
            "Reconnecte-toi pour repartir sur des données à jour.",
            result.errorHint,
        )
    }

    @Test
    fun `recovers silently when the Pronote session has expired`() = runBlocking {
        val repository = FakeRepository(snapshot(grade("a")), PronoteError.SessionExpired).apply {
            failuresBeforeSuccess = 1
            restoredSession = restorableSession()
        }
        val manager = SyncManager(repository) { true }

        val result = manager.sync("acct")

        assertTrue(result.ok)
        assertNull(result.error)
        // One silent reconnect, then the retried sync — no banner for the user.
        assertEquals(1, repository.restoreCount)
        assertEquals(2, repository.syncCount)
    }

    @Test
    fun `reconnecte aussi quand le serveur refuse le jeton`() = runBlocking {
        // Un jeton mobile mort remonte du connecteur comme « identifiants
        // incorrects ». Sans cette reprise, l'utilisateur devait se déconnecter
        // puis se reconnecter à la main pour ravoir ses notes à jour.
        val repository = FakeRepository(snapshot(grade("a")), PronoteError.InvalidCredentials).apply {
            failuresBeforeSuccess = 1
            restoredSession = restorableSession()
        }
        val manager = SyncManager(repository) { true }

        val result = manager.sync("acct")

        assertTrue(result.ok)
        assertNull(result.error)
        assertEquals(1, repository.restoreCount)
        assertEquals(2, repository.syncCount)
    }

    @Test
    fun `une session refusee est rejouee trois fois avant de rendre la main`() = runBlocking {
        // La session ne doit jamais « se déconnecter » sur un refus passager :
        // chaque échec déclenche une reconnexion silencieuse, et on ne renonce
        // qu'après toutes les tentatives — sans rien effacer du coffre.
        val repository = FakeRepository(snapshot(grade("a")), PronoteError.SessionExpired).apply {
            restoredSession = restorableSession()
        }
        val manager = SyncManager(repository) { true }

        val result = manager.sync("acct")

        assertFalse(result.ok)
        assertEquals("Ta session Pronote a expiré.", result.error)
        assertEquals(SyncManager.SILENT_RECONNECTS, repository.restoreCount)
        assertEquals(SyncManager.SILENT_RECONNECTS + 1, repository.syncCount)
    }

    @Test
    fun `does not retry other errors even when the session can be restored`() = runBlocking {
        val repository = FakeRepository(snapshot(grade("a")), PronoteError.PronoteUnavailable).apply {
            restoredSession = restorableSession()
        }
        val manager = SyncManager(repository) { true }

        val result = manager.sync("acct")

        assertFalse(result.ok)
        assertEquals("Pronote ne répond pas pour le moment.", result.error)
        assertEquals(0, repository.restoreCount)
        assertEquals(1, repository.syncCount)
    }

    @Test
    fun `cesse les synchros automatiques quand Pronote bloque les connexions`() = runBlocking {
        // Après une mise à jour, la session est morte et chaque synchro
        // automatique renvoyait un login de plus — ce qui entretenait la
        // suspension d'IP côté Pronote. On attend sans rien envoyer.
        val repository = FakeRepository(snapshot(grade("a")), PronoteError.TooManyAttempts)
        val manager = SyncManager(repository) { true }

        val first = manager.sync("acct")
        assertFalse(first.ok)
        assertEquals("Pronote bloque les connexions pour le moment.", first.error)
        assertEquals(1, repository.syncCount)

        val second = manager.sync("acct")
        assertEquals(1, repository.syncCount)
        assertEquals("Pronote bloque les connexions pour le moment.", second.error)
        assertEquals(
            "Trop de tentatives en peu de temps : attends environ une heure, puis réessaie.",
            second.errorHint,
        )
    }

    @Test
    fun `une actualisation manuelle reste possible pendant le blocage`() = runBlocking {
        val repository = FakeRepository(snapshot(grade("a")), PronoteError.TooManyAttempts).apply {
            failuresBeforeSuccess = 1
        }
        val manager = SyncManager(repository) { true }

        manager.sync("acct")
        val forced = manager.sync("acct", force = true)

        assertTrue(forced.ok)
        assertEquals(2, repository.syncCount)
    }

    @Test
    fun `never syncs while offline`() = runBlocking {
        val repository = FakeRepository(snapshot(grade("a")))
        val manager = SyncManager(repository) { false }

        val result = manager.sync("acct")

        assertEquals(SyncManager.SkipReason.OFFLINE, result.skipped)
        assertEquals(0, repository.syncCount)
    }

    @Test
    fun `skips a second sync that is too recent`() = runBlocking {
        val repository = FakeRepository(snapshot(grade("a")))
        val manager = SyncManager(repository) { true }

        assertTrue(manager.sync("acct").ok)
        val second = manager.sync("acct")

        assertEquals(SyncManager.SkipReason.RECENT, second.skipped)
        assertEquals(1, repository.syncCount)
    }

    @Test
    fun `force bypasses the recent guard`() = runBlocking {
        val repository = FakeRepository(snapshot(grade("a")))
        val manager = SyncManager(repository) { true }

        manager.sync("acct")
        val forced = manager.sync("acct", force = true)

        assertTrue(forced.ok)
        assertEquals(2, repository.syncCount)
    }

    @Test
    fun `syncs the period shown by the user, not the server default`() = runBlocking {
        // Une note ajoutée après coup au 1er trimestre doit rafraîchir le 1er
        // trimestre, même quand le serveur considère le 2e comme courant.
        val repository = FakeRepository(snapshot(grade("a"))).apply {
            selectedPeriod = "periode-t1"
        }
        val manager = SyncManager(repository) { true }

        val result = manager.sync("acct")

        assertTrue(result.ok)
        assertEquals("periode-t1", repository.lastSyncPeriod)
    }

    @Test
    fun `keeps syncing the selected period after a silent reconnect`() = runBlocking {
        val repository = FakeRepository(snapshot(grade("a")), PronoteError.SessionExpired).apply {
            failuresBeforeSuccess = 1
            restoredSession = restorableSession()
            selectedPeriod = "periode-t1"
        }
        val manager = SyncManager(repository) { true }

        assertTrue(manager.sync("acct").ok)
        assertEquals("periode-t1", repository.lastSyncPeriod)
    }

    @Test
    fun `detects the grades added since the previous sync`() {
        val before = snapshot(grade("a"))
        val after = snapshot(grade("a"), grade("b"))

        val detected = SyncManager.detectNewGrades(before, after)

        assertEquals(listOf("b"), detected.map { it.id })
    }

    @Test
    fun `does not report new grades on a first sync`() {
        assertEquals(emptyList<Grade>(), SyncManager.detectNewGrades(null, snapshot(grade("a"))))
    }

    @Test
    fun `never announces a grade older than the cutoff`() {
        val before = snapshot(grade("a"))
        val after = snapshot(
            grade("a"),
            grade("month-old", date = isoDaysAgo(SyncManager.MAX_NOTIFY_AGE_DAYS + 30)),
        )

        assertEquals(emptyList<Grade>(), SyncManager.detectNewGrades(before, after))
    }

    @Test
    fun `announces a grade that is recent enough`() {
        val before = snapshot(grade("a"))
        val after = snapshot(grade("a"), grade("fresh", date = isoDaysAgo(2)))

        assertEquals(listOf("fresh"), SyncManager.detectNewGrades(before, after).map { it.id })
    }

    @Test
    fun `announces the most recent grade first`() {
        val before = snapshot(grade("a"))
        val after = snapshot(
            grade("a"),
            grade("older", date = isoDaysAgo(4)),
            grade("newer", date = isoDaysAgo(1)),
        )

        assertEquals(
            listOf("newer", "older"),
            SyncManager.detectNewGrades(before, after).map { it.id },
        )
    }

    @Test
    fun `treats a wholesale id change as a new baseline, not as new grades`() {
        val before = snapshot(grade("a"), grade("b"), grade("c"), grade("d"), grade("e"))
        val after = snapshot(grade("x"), grade("y"), grade("z"), grade("w"), grade("v"))

        assertEquals(emptyList<Grade>(), SyncManager.detectNewGrades(before, after))
    }

    @Test
    fun `reports the average change around a successful sync`() = runBlocking {
        val repository = FakeRepository(snapshot(grade("a")))
        val manager = SyncManager(repository) { true }

        val result = manager.sync("acct")

        assertTrue(result.ok)
        assertNull(result.previousAverage)
        assertTrue(result.error == null && result.errorHint == null)
    }
}