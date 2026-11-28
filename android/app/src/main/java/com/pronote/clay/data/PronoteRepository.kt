package com.pronote.clay.data

import com.pronote.clay.core.AverageCalculator
import com.pronote.clay.core.Logger
import com.pronote.clay.core.PronoteError
import kotlinx.coroutines.delay
import com.pronote.clay.core.PronoteResult
import com.pronote.clay.data.local.LocalDataSource
import com.pronote.clay.data.remote.PronoteCompatibilityLayer
import com.pronote.clay.data.remote.PronoteAttendanceDto
import com.pronote.clay.data.remote.PronoteHomeworkDto
import com.pronote.clay.data.remote.PronoteLessonDto
import com.pronote.clay.data.remote.PronoteRawPayload
import com.pronote.clay.data.security.SecureCredentialsManager
import com.pronote.clay.domain.AttendanceEntry
import com.pronote.clay.domain.AverageSource
import com.pronote.clay.domain.GeneralAverage
import com.pronote.clay.domain.Homework
import com.pronote.clay.domain.HistoryPoint
import com.pronote.clay.domain.PronoteSession
import com.pronote.clay.domain.SchoolSnapshot
import com.pronote.clay.domain.SubjectAverage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A grade is worth showing on the history chart when its date is parsable and
 * at most [maxAgeDays] days old. An unparsable date keeps the point visible:
 * dropping it would silently erase history for a format quirk.
 */
fun isWithinPeriod(isoDate: String, todayMs: Long, maxAgeDays: Long): Boolean {
    val millis = try {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate)?.time
    } catch (_: Exception) {
        null
    } ?: return true
    return millis >= todayMs - maxAgeDays * HistoryPeriods.DAY_MS
}

/** Chart periods, from one week to the full (school) year. */
object HistoryPeriods {
    const val WEEK_DAYS = 7L
    const val MONTH_DAYS = 30L
    const val QUARTER_DAYS = 90L
    const val YEAR_DAYS = 365L

    const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Nothing is filtered out beyond the year view. */
    const val ALL_DAYS = Long.MAX_VALUE
}

/**
 * [PronoteRepository] — the abstraction the rest of the app depends on.
 *
 * The repository decides whether data comes from the local cache, from Pronote,
 * or from both (cache-first + background refresh).
 */
interface PronoteRepository {
    suspend fun login(
        accountId: String,
        schoolUrl: String,
        username: String,
        password: String,
    ): PronoteResult<PronoteSession>

    suspend fun refreshSession(accountId: String): PronoteSession?

    fun getCachedSnapshot(accountId: String): SchoolSnapshot?

    /**
     * Récupère les notes. [periodId] choisit un trimestre précis ; null garde
     * la période courante choisie par l'établissement.
     */
    suspend fun sync(accountId: String, periodId: String? = null): PronoteResult<SchoolSnapshot>

    /** Périodes proposées par l'établissement, mémorisées à la dernière synchro. */
    fun availablePeriods(accountId: String): List<com.pronote.clay.domain.PeriodOption>

    /** Trimestre actuellement affiché, ou null tant qu'aucune synchro n'a eu lieu. */
    fun selectedPeriodId(accountId: String): String?

    suspend fun logout(accountId: String)

    suspend fun deleteLocalData(accountId: String)

    /**
     * History narrowed to a period (7 days, 1 month, …) for the chart.
     * Cache-only: never touches the network.
     */
    fun getHistory(accountId: String, days: Long): List<HistoryPoint>

    /**
     * Timetable of the week starting on [weekStartIso] (a Monday, ISO date).
     * Network-backed with a short in-memory cache so swiping between weeks
     * stays instant on re-visit.
     */
    suspend fun getTimetable(accountId: String, weekStartIso: String): PronoteResult<List<com.pronote.clay.domain.TimetableLesson>>

    /**
     * Cahier de textes entre deux dates ISO incluses.
     *
     * Réseau d'abord, puis cache disque : un devoir déjà vu reste consultable
     * dans le bus, et le widget n'a jamais besoin d'une requête.
     */
    suspend fun getHomework(
        accountId: String,
        fromIso: String,
        toIso: String,
    ): PronoteResult<List<Homework>>

    /** Absences et retards de l'année, avec le même repli hors-ligne. */
    suspend fun getAttendance(accountId: String): PronoteResult<List<AttendanceEntry>>

    /** Devoirs mémorisés, sans réseau. */
    fun cachedHomework(accountId: String): List<Homework>

    /** Absences et retards mémorisés, sans réseau. */
    fun cachedAttendance(accountId: String): List<AttendanceEntry>

    /** Dernière semaine d'emploi du temps vue, pour le widget du jour. */
    fun cachedWeek(accountId: String): List<com.pronote.clay.domain.TimetableLesson>
}

class PronoteRepositoryImpl(
    private val connector: PronoteCompatibilityLayer,
    private val sessions: SessionManager,
    private val local: LocalDataSource,
    /** Notified after every cache write so the widgets never show stale data. */
    private val onSnapshotSaved: () -> Unit = {},
) : PronoteRepository {

    /** Cache-first: the caller renders this immediately, before any network. */
    override fun getCachedSnapshot(accountId: String): SchoolSnapshot? =
        local.loadSnapshot(accountId)

    override suspend fun login(
        accountId: String,
        schoolUrl: String,
        username: String,
        password: String,
    ): PronoteResult<PronoteSession> {
        val result = connector.authenticate(schoolUrl, username, password)
        if (result is PronoteResult.Failure) return result

        val auth = (result as PronoteResult.Success).value
        val session = PronoteSession(
            token = auth.token,
            expiresAt = auth.expiresAt,
            schoolUrl = schoolUrl,
            schoolName = auth.schoolName,
            connected = true,
        )

        // Prefer the token as the primary path; the password stays in the
        // encrypted vault as the safety net. PRONOTE can invalidate a mobile
        // token at any time (another device, server purge, uuid change): with
        // the password kept, [SessionManager] silently re-mints a token and
        // the user never sees a login screen again.
        sessions.save(
            accountId,
            SecureCredentialsManager.StoredCredentials(
                schoolUrl = schoolUrl,
                schoolName = auth.schoolName,
                username = username,
                token = auth.token.takeIf { it.isNotEmpty() },
                expiresAt = auth.expiresAt,
                deviceUuid = auth.deviceUuid,
                password = password.takeIf { it.isNotBlank() },
            ),
        )
        local.saveSessionMeta(accountId, session)
        if (auth.payload.periods.isNotEmpty()) {
            local.savePeriods(
                accountId,
                auth.payload.periods.mapNotNull { dto ->
                    val id = dto.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    com.pronote.clay.domain.PeriodOption(id, dto.name?.takeIf { it.isNotBlank() } ?: id)
                },
            )
        }
        auth.payload.periodId?.let { local.saveSelectedPeriod(accountId, it) }
        local.saveSnapshot(accountId, toSnapshot(auth.payload), auth.payload.periodId)
        onSnapshotSaved()
        return PronoteResult.Success(session)
    }

    override suspend fun refreshSession(accountId: String): PronoteSession? =
        sessions.refreshSession(accountId)

    override fun availablePeriods(accountId: String): List<com.pronote.clay.domain.PeriodOption> =
        local.loadPeriods(accountId)

    override fun selectedPeriodId(accountId: String): String? =
        local.loadSelectedPeriod(accountId)

    override suspend fun sync(accountId: String, periodId: String?): PronoteResult<SchoolSnapshot> {
        var stored = sessions.load(accountId)
            ?: return PronoteResult.Failure(PronoteError.SessionExpired)

        var result = fetchSnapshot(stored, periodId)

        // Une session Pronote meurt sans prévenir : autre appareil connecté,
        // purge côté serveur, jeton jamais émis… Le mot de passe gardé dans le
        // coffre permet de la refabriquer. On rejoue donc la collecte, plusieurs
        // fois de suite avec une pause croissante, au lieu de renvoyer
        // l'utilisateur à l'écran de connexion.
        var recoveries = 0
        while (recoveries < MAX_SILENT_RECOVERIES) {
            val failure = result as? PronoteResult.Failure ?: break
            if (!canRecover(failure.error)) break
            if (sessions.refreshSession(accountId) == null) break
            stored = sessions.load(accountId) ?: stored
            delay(SILENT_RECOVERY_PAUSE_MS * (recoveries + 1))
            result = fetchSnapshot(stored, periodId)
            recoveries++
        }

        if (result is PronoteResult.Failure) return result

        val payload = (result as PronoteResult.Success).value
        if (payload.periods.isNotEmpty()) {
            local.savePeriods(
                accountId,
                payload.periods.mapNotNull { dto ->
                    val id = dto.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    com.pronote.clay.domain.PeriodOption(id, dto.name?.takeIf { it.isNotBlank() } ?: id)
                },
            )
        }

        // L'instantané déjà mémorisé pour CE trimestre : il permet de repérer
        // une moyenne officielle que Pronote n'a pas encore recalculée.
        val snapshot = toSnapshot(payload, payload.periodId?.let { local.loadSnapshot(accountId, it) })
        val activePeriod = payload.periodId
        local.saveSnapshot(accountId, snapshot, activePeriod)
        if (activePeriod != null) local.saveSelectedPeriod(accountId, activePeriod)
        onSnapshotSaved()
        Logger.info("Grades updated")
        return PronoteResult.Success(snapshot)
    }

    /** Une seule collecte, avec les identifiants déjà stockés. */
    private suspend fun fetchSnapshot(
        stored: SecureCredentialsManager.StoredCredentials,
        periodId: String? = null,
    ): PronoteResult<PronoteRawPayload> =
        connector.fetchAll(
            token = stored.token.orEmpty(),
            schoolUrl = stored.schoolUrl,
            username = stored.username,
            deviceUuid = stored.deviceUuid.orEmpty(),
            periodId = periodId,
        )

    /**
     * Erreurs qu'une reconnexion silencieuse peut corriger : sans ça, une
     * session périmée s'affiche comme « identifiants incorrects » et oblige à
     * se déconnecter puis se reconnecter à la main.
     */
    private fun canRecover(error: PronoteError): Boolean =
        error is PronoteError.SessionExpired || error is PronoteError.InvalidCredentials

    /**
     * Historique de l'année : fusionne les points de tous les trimestres déjà
     * consultés. Sans ça, la courbe « Année » ne montrait que le trimestre en
     * cours et disparaissait dès qu'il n'avait qu'une note.
     */
    override fun getHistory(accountId: String, days: Long): List<HistoryPoint> {
        val merged = local.loadSnapshots(accountId)
            .flatMap { it.history }
            .distinctBy { it.date }
            .sortedBy { it.date }
        if (merged.isEmpty()) return emptyList()
        return filterHistory(merged, days)
    }

    /** Small per-week timetable cache — survives week-to-week swipes. */
    private val timetableCache = object : LinkedHashMap<String, List<com.pronote.clay.domain.TimetableLesson>>(8) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<com.pronote.clay.domain.TimetableLesson>>): Boolean =
            size > 6
    }

    override suspend fun getTimetable(
        accountId: String,
        weekStartIso: String,
    ): PronoteResult<List<com.pronote.clay.domain.TimetableLesson>> {
        timetableCache["$accountId|$weekStartIso"]?.let { return PronoteResult.Success(it) }

        var stored = sessions.load(accountId)
            ?: return PronoteResult.Failure(PronoteError.SessionExpired)

        var result = fetchTimetable(stored, weekStartIso)

        if (result is PronoteResult.Failure) {
            // Une seule reconnexion silencieuse, par la même chaîne que la
            // synchronisation (jeton mobile, puis mot de passe) : un jeton
            // périmé côté serveur ne doit jamais s'afficher comme de mauvais
            // identifiants sur l'emploi du temps.
            if (sessions.refreshSession(accountId) != null) {
                stored = sessions.load(accountId) ?: return result
                result = fetchTimetable(stored, weekStartIso)
            }
        }

        if (result is PronoteResult.Failure) {
            // Hors-ligne : la semaine déjà mémorisée reste consultable, mais
            // seulement si c'est bien celle demandée — afficher une autre
            // semaine sous ce titre serait pire qu'un message d'erreur.
            val cached = local.loadWeek(accountId)?.takeIf { it.weekStartIso == weekStartIso }
            return if (cached != null && cached.lessons.isNotEmpty()) {
                PronoteResult.Success(cached.lessons)
            } else {
                result
            }
        }

        val lessons = (result as PronoteResult.Success).value
            .mapNotNull { Mappers.mapLesson(it) }
            .sortedBy { it.startIso }
        timetableCache["$accountId|$weekStartIso"] = lessons
        if (lessons.isNotEmpty()) local.saveWeek(accountId, weekStartIso, lessons)
        return PronoteResult.Success(lessons)
    }

    override fun cachedHomework(accountId: String): List<Homework> =
        local.loadHomework(accountId)

    override fun cachedAttendance(accountId: String): List<AttendanceEntry> =
        local.loadAttendance(accountId)

    override fun cachedWeek(accountId: String): List<com.pronote.clay.domain.TimetableLesson> =
        local.loadWeek(accountId)?.lessons.orEmpty()

    override suspend fun getHomework(
        accountId: String,
        fromIso: String,
        toIso: String,
    ): PronoteResult<List<Homework>> {
        var stored = sessions.load(accountId)
            ?: return PronoteResult.Failure(PronoteError.SessionExpired)

        var result = fetchHomework(stored, fromIso, toIso)
        if (result is PronoteResult.Failure && sessions.refreshSession(accountId) != null) {
            stored = sessions.load(accountId) ?: return result
            result = fetchHomework(stored, fromIso, toIso)
        }

        if (result is PronoteResult.Failure) {
            val cached = local.loadHomework(accountId)
                .filter { it.dueDate.isEmpty() || it.dueDate in fromIso..toIso }
            return if (cached.isEmpty()) result else PronoteResult.Success(cached)
        }

        val homework = (result as PronoteResult.Success).value
            .map { Mappers.mapHomework(it) }
            .filter { it.description.isNotBlank() || it.attachments.isNotEmpty() }
            .sortedBy { it.sortKey }
        local.saveHomework(accountId, homework)
        return PronoteResult.Success(homework)
    }

    override suspend fun getAttendance(accountId: String): PronoteResult<List<AttendanceEntry>> {
        var stored = sessions.load(accountId)
            ?: return PronoteResult.Failure(PronoteError.SessionExpired)

        var result = fetchAttendance(stored)
        if (result is PronoteResult.Failure && sessions.refreshSession(accountId) != null) {
            stored = sessions.load(accountId) ?: return result
            result = fetchAttendance(stored)
        }

        if (result is PronoteResult.Failure) {
            val cached = local.loadAttendance(accountId)
            return if (cached.isEmpty()) result else PronoteResult.Success(cached)
        }

        val entries = (result as PronoteResult.Success).value
            .map { Mappers.mapAttendance(it) }
            .sortedBy { it.sortKey }
        local.saveAttendance(accountId, entries)
        return PronoteResult.Success(entries)
    }

    private suspend fun fetchHomework(
        stored: SecureCredentialsManager.StoredCredentials,
        fromIso: String,
        toIso: String,
    ): PronoteResult<List<PronoteHomeworkDto>> =
        connector.fetchHomework(
            token = stored.token.orEmpty(),
            schoolUrl = stored.schoolUrl,
            username = stored.username,
            deviceUuid = stored.deviceUuid.orEmpty(),
            fromIso = fromIso,
            toIso = toIso,
        )

    private suspend fun fetchAttendance(
        stored: SecureCredentialsManager.StoredCredentials,
    ): PronoteResult<List<PronoteAttendanceDto>> =
        connector.fetchAttendance(
            token = stored.token.orEmpty(),
            schoolUrl = stored.schoolUrl,
            username = stored.username,
            deviceUuid = stored.deviceUuid.orEmpty(),
        )

    private suspend fun fetchTimetable(
        stored: SecureCredentialsManager.StoredCredentials,
        weekStartIso: String,
    ): PronoteResult<List<PronoteLessonDto>> =
        connector.fetchTimetable(
            token = stored.token.orEmpty(),
            schoolUrl = stored.schoolUrl,
            username = stored.username,
            deviceUuid = stored.deviceUuid.orEmpty(),
            weekStartIso = weekStartIso,
        )

    /** "Supprimer mes données" — snapshot, session metadata and secrets. */
    override suspend fun logout(accountId: String) {
        sessions.load(accountId)?.token?.let { connector.logout(it) }
        sessions.clearSession(accountId)
        local.clearSessionMeta(accountId)
    }

    override suspend fun deleteLocalData(accountId: String) {
        local.clearAccount(accountId)
        local.clearSessionMeta(accountId)
        sessions.clearSession(accountId)
    }

    /**
     * Remote payload -> local snapshot.
     *
     * The general average is OFFICIAL when Pronote provides one, otherwise it is
     * CALCULATED locally so a hidden average still shows up. [previous] is the
     * snapshot of the same period from the last sync, used to notice an
     * official average that Pronote has not recalculated yet.
     */
    fun toSnapshot(payload: PronoteRawPayload, previous: SchoolSnapshot? = null): SchoolSnapshot {
        val student = Mappers.mapStudent(payload.student)
        val subjects = payload.subjects.mapIndexed { index, dto -> Mappers.mapSubject(dto, index) }

        // Grade DTOs reference subjects by connector id: remap onto domain ids.
        val idMap = payload.subjects.mapIndexed { index, dto ->
            (dto.id ?: "subject-$index") to subjects[index].id
        }.toMap()

        val grades = payload.grades.mapIndexed { index, dto ->
            val grade = Mappers.mapGrade(dto, index)
            grade.copy(subjectId = idMap[dto.matiere] ?: grade.subjectId)
        }

        val remoteAverages = payload.subjectAverages.map { dto ->
            val mapped = Mappers.mapSubjectAverage(dto)
            mapped.copy(subjectId = idMap[dto.matiere] ?: mapped.subjectId)
        }

        val subjectAverages = subjects.map { subject ->
            val remote = remoteAverages.find { it.subjectId == subject.id }
            val computed = AverageCalculator.subjectAverage(subject, grades)
            SubjectAverage(
                subjectId = subject.id,
                value = remote?.value ?: computed ?: 0.0,
                period = remote?.period ?: payload.period,
                source = if (remote != null) AverageSource.OFFICIAL else AverageSource.CALCULATED,
            )
        }

        val computed = AverageCalculator.resolveGeneralAverage(
            official = payload.generalAverage,
            subjects = subjects,
            grades = grades,
            subjectAverages = remoteAverages,
            previousOfficial = previous?.generalAverage?.value,
            gradesChanged = previous != null &&
                gradeFingerprint(previous.grades) != gradeFingerprint(grades),
        )

        val generalAverage = computed?.let { (value, isCalculated) ->
            GeneralAverage(
                value = value,
                period = payload.period,
                source = if (isCalculated) AverageSource.CALCULATED else AverageSource.OFFICIAL,
                updatedAt = System.currentTimeMillis(),
            )
        }

        return SchoolSnapshot(
            student = student,
            subjects = subjects,
            grades = grades,
            subjectAverages = subjectAverages,
            generalAverage = generalAverage,
            history = buildHistory(grades, generalAverage),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** Une note ajoutée, retirée ou modifiée change cette empreinte. */
    private fun gradeFingerprint(grades: List<com.pronote.clay.domain.Grade>): Set<String> =
        grades.map {
            "${it.id}|${it.value}|${it.maxValue}|${it.coefficient}|${it.countsTowardAverage}"
        }.toSet()

    /** A compact history built from the grades we know about. */
    private fun buildHistory(
        grades: List<com.pronote.clay.domain.Grade>,
        current: GeneralAverage?,
    ): List<HistoryPoint> {
        val sorted = grades.sortedBy { it.date }
        if (sorted.isEmpty()) return emptyList()

        val points = mutableListOf<HistoryPoint>()
        val running = mutableListOf<com.pronote.clay.domain.Grade>()
        var lastBucket = ""

        for (grade in sorted) {
            running.add(grade)
            val bucket = grade.date.take(7) // month
            val value = runningAverage(running)
            if (bucket != lastBucket && value != null) {
                points.add(HistoryPoint(grade.date, value))
                lastBucket = bucket
            }
        }

        current?.let { avg ->
            points.add(HistoryPoint(todayIso(), avg.value))
        }

        return points
    }

    private fun runningAverage(grades: List<com.pronote.clay.domain.Grade>): Double? {
        val significant = AverageCalculator.significantGrades(grades)
        if (significant.isEmpty()) return null
        val rows = significant.map {
            AverageCalculator.normalizeGrade(it) to it.coefficient
        }
        return AverageCalculator.weightedMean(rows)
    }

    private fun todayIso(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        /**
         * Keeps only the history points from the last [days] days. The most
         * recent point (the snapshot's "today" value) always stays, so the
         * chart ends on the current average at every period.
         */
        fun filterHistory(history: List<HistoryPoint>, days: Long, todayMs: Long = System.currentTimeMillis()): List<HistoryPoint> {
            if (history.isEmpty()) return emptyList()
            if (days >= HistoryPeriods.YEAR_DAYS) return history
            val scoped = history.filter {
                isWithinPeriod(it.date, todayMs, days) || it == history.last()
            }
            // Too few points for the window: keep the two most recent ones
            // so the chart stays readable without jumping back to the full
            // history — a week view must not look like the year view.
            return if (scoped.size >= MIN_POINTS_PER_WINDOW) scoped else history.takeLast(MIN_POINTS_PER_WINDOW)
        }

        /** Below that a window is unreadable; show the full history instead. */
        const val MIN_POINTS_PER_WINDOW = 2
    }
}

/** Reconnexions silencieuses tentées à l'intérieur d'une même collecte. */
private const val MAX_SILENT_RECOVERIES = 3

/** Pause qui grandit entre deux reconnexions (anti-rafale côté serveur). */
private const val SILENT_RECOVERY_PAUSE_MS = 250L
