package com.pronote.clay.data.remote

import com.pronote.clay.core.Logger
import com.pronote.clay.core.PronoteError
import com.pronote.clay.core.PronoteResult
import kotlinx.coroutines.CancellationException

/**
 * Remote DTOs — the wire shape of a Pronote connector.
 * These never leave the data layer: `GradeMapper` / `AverageMapper` translate
 * them into domain models.
 *
 * Field names mirror the French Pronote payload so a real connector can drop in
 * behind [PronoteDataSource] without touching the rest of the app.
 */
data class PronoteStudentDto(
    val id: String? = null,
    val prenom: String? = null,
    val nom: String? = null,
    val classe: String? = null,
    val etablissement: String? = null,
)

data class PronoteSubjectDto(
    val id: String? = null,
    val nom: String? = null,
    val nomCourt: String? = null,
    val professeur: String? = null,
    val coefficient: Double? = null,
)

data class PronoteGradeDto(
    val id: String? = null,
    val matiere: String? = null,
    val intitule: String? = null,
    val note: Double = 0.0,
    val bareme: Double = 20.0,
    val coefficient: Double = 1.0,
    val date: String? = null,
    val commentaire: String? = null,
    /** Pronote marks some grades as "non significatif". */
    val nonSignificatif: Boolean = false,
)

data class PronoteSubjectAverageDto(
    val matiere: String? = null,
    val moyenne: Double = 0.0,
    val periode: String? = null,
)

/** Une période Pronote : trimestre, semestre ou année. */
data class PronotePeriodDto(
    val id: String? = null,
    val name: String? = null,
)

data class PronoteRawPayload(
    val student: PronoteStudentDto,
    val subjects: List<PronoteSubjectDto>,
    val grades: List<PronoteGradeDto>,
    val subjectAverages: List<PronoteSubjectAverageDto>,
    /** Null when the establishment hides the general average. */
    val generalAverage: Double? = null,
    val period: String = "T2",
    /** Toutes les périodes proposées par l'établissement (T1, T2, T3…). */
    val periods: List<PronotePeriodDto> = emptyList(),
    /** Période réellement demandée, pour que l'app sache quoi afficher. */
    val periodId: String? = null,
)

/** One course of the timetable, in the connector's raw shape. */
data class PronoteLessonDto(
    /** Subject id as Pronote knows it (may be absent on activities). */
    val matiere: String? = null,
    /** Subject display name, e.g. "MATHS". */
    val nomMatiere: String? = null,
    val professeur: String? = null,
    val salle: String? = null,
    val groupe: String? = null,
    /** ISO start, e.g. "2026-10-08T08:00:00". */
    val debut: String? = null,
    /** ISO end. */
    val fin: String? = null,
    /** Cancelled course ("cours annulé"). */
    val annule: Boolean = false,
    /** Detention, test label… free text under the subject. */
    val memo: String? = null,
)

/**
 * Un devoir du cahier de textes (`PageCahierDeTexte`, onglet 88).
 *
 * Dates ISO, consigne en texte simple : le HTML Pronote est aplati côté
 * connecteur pour que le cache disque et l'interface n'aient rien à en savoir.
 */
data class PronoteHomeworkDto(
    val id: String? = null,
    val matiere: String? = null,
    val nomMatiere: String? = null,
    /** ISO date de remise. */
    val pourLe: String? = null,
    /** ISO date de la consigne. */
    val donneLe: String? = null,
    val description: String? = null,
    val fait: Boolean = false,
    val piecesJointes: List<String> = emptyList(),
)

/**
 * Une absence (`kind = "absence"`, code Pronote `G` 13) ou un retard
 * (`kind = "delay"`, code `G` 14), telle que `PagePresence` la renvoie.
 */
data class PronoteAttendanceDto(
    val id: String? = null,
    val kind: String? = null,
    /** ISO date-heure de début. */
    val debut: String? = null,
    /** ISO date-heure de fin (absences) ou heure du retard. */
    val fin: String? = null,
    val minutes: Int = 0,
    val justifie: Boolean = false,
    val motif: String? = null,
)

data class PronoteAuthResult(
    /** Opaque session token — kept only in the encrypted vault. */
    val token: String,
    val expiresAt: Long,
    val schoolName: String,
    val payload: PronoteRawPayload,
    /** Stable per-account identifier required to refresh a mobile token. */
    val deviceUuid: String? = null,
)

/**
 * [PronoteDataSource] is the ONLY thing the app knows about Pronote.
 *
 * A real connector (open-source library or direct client) implements this
 * interface. Everything above it — repository, use cases, ViewModels, UI, local
 * cache — stays untouched.
 *
 * SECURITY: this layer must never bypass an authentication, a CAPTCHA or any
 * security measure. [PronoteError.CaptchaRequired] is a first-class outcome.
 */
interface PronoteDataSource {
    /** Human label of the connector strategy currently in use. */
    val strategy: String

    suspend fun authenticate(
        schoolUrl: String,
        username: String,
        password: String,
    ): PronoteResult<PronoteAuthResult>

    suspend fun restoreSession(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
    ): PronoteResult<PronoteAuthResult>

    /**
     * Notes et moyennes. [periodId] choisit la période Pronote (trimestre) ;
     * null laisse le serveur envoyer la période courante.
     */
    suspend fun fetchAll(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
        periodId: String? = null,
    ): PronoteResult<PronoteRawPayload>

    /**
     * Timetable of one week, from its Monday (ISO date). A live per-week
     * query: sessions stay on the connector, results are never cached here.
     */
    suspend fun fetchTimetable(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
        weekStartIso: String,
    ): PronoteResult<List<PronoteLessonDto>>

    /**
     * Cahier de textes entre deux dates ISO incluses, une requête par plage.
     * Le connecteur choisit les semaines Pronote correspondantes.
     */
    suspend fun fetchHomework(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
        fromIso: String,
        toIso: String,
    ): PronoteResult<List<PronoteHomeworkDto>>

    /** Absences et retards de l'année scolaire en cours. */
    suspend fun fetchAttendance(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
    ): PronoteResult<List<PronoteAttendanceDto>>

    suspend fun logout(token: String)
}

/**
 * PronoteCompatibilityLayer — keeps a connector change from crashing the app.
 * It validates what a connector returned and raises typed errors instead of raw
 * exceptions.
 */
class PronoteCompatibilityLayer(private val source: PronoteDataSource) {

    val strategy: String get() = source.strategy

    suspend fun authenticate(
        schoolUrl: String,
        username: String,
        password: String,
    ): PronoteResult<PronoteAuthResult> = guard {
        source.authenticate(schoolUrl, username, password)
    }

    suspend fun restoreSession(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
    ): PronoteResult<PronoteAuthResult> = guard {
        source.restoreSession(token, schoolUrl, username, deviceUuid)
    }

    suspend fun fetchAll(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
        periodId: String? = null,
    ): PronoteResult<PronoteRawPayload> = guard {
        when (val result = source.fetchAll(token, schoolUrl, username, deviceUuid, periodId)) {
            is PronoteResult.Success -> PronoteResult.Success(assertPayload(result.value))
            is PronoteResult.Failure -> result
        }
    }

    suspend fun fetchTimetable(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
        weekStartIso: String,
    ): PronoteResult<List<PronoteLessonDto>> = guard {
        source.fetchTimetable(token, schoolUrl, username, deviceUuid, weekStartIso)
    }

    suspend fun fetchHomework(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
        fromIso: String,
        toIso: String,
    ): PronoteResult<List<PronoteHomeworkDto>> = guard {
        source.fetchHomework(token, schoolUrl, username, deviceUuid, fromIso, toIso)
    }

    suspend fun fetchAttendance(
        token: String,
        schoolUrl: String,
        username: String,
        deviceUuid: String,
    ): PronoteResult<List<PronoteAttendanceDto>> = guard {
        source.fetchAttendance(token, schoolUrl, username, deviceUuid)
    }

    suspend fun logout(token: String) {
        // Logging out must never fail loudly: the local session is cleared anyway.
        try {
            source.logout(token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Logger.warn("Connector logout failed")
        }
    }

    /**
     * Exécute un appel du connecteur en traduisant toute exception en échec typé.
     *
     * Seule l'annulation de coroutine remonte telle quelle : elle signifie que
     * l'appelant a abandonné, pas que Pronote a échoué.
     */
    private suspend fun <T> guard(block: suspend () -> PronoteResult<T>): PronoteResult<T> =
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PronoteResult.Failure(normalize(error))
        }

    private fun assertPayload(payload: PronoteRawPayload): PronoteRawPayload = payload

    private fun normalize(error: Throwable): PronoteError = when (error) {
        is PronoteError -> error
        else -> {
            Logger.error("Connector error", error)
            PronoteError.UnknownError
        }
    }
}
