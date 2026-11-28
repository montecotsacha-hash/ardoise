package com.pronote.clay.domain

/**
 * Domain models — completely independent from the Pronote connector.
 * The UI never touches a DTO or a storage entity directly.
 */

data class Student(
    val id: String,
    val firstName: String,
    val lastName: String,
    val className: String,
    val schoolName: String,
)

data class Subject(
    val id: String,
    val name: String,
    val shortName: String,
    val teacher: String,
    val coefficient: Double,
    /** Index into the clay accent palette. */
    val colorIndex: Int,
)

data class Grade(
    val id: String,
    val subjectId: String,
    val title: String,
    val value: Double,
    val maxValue: Double,
    val coefficient: Double,
    /** ISO date, YYYY-MM-DD. */
    val date: String,
    val comment: String? = null,
    /** false for grades the teacher marked "non significatif". */
    val countsTowardAverage: Boolean = true,
)

/** Where an average came from — never blur the two. */
enum class AverageSource { OFFICIAL, CALCULATED }

data class SubjectAverage(
    val subjectId: String,
    val value: Double,
    val period: String,
    val source: AverageSource,
)

data class GeneralAverage(
    val value: Double,
    val period: String,
    val source: AverageSource,
    val updatedAt: Long,
)

data class HistoryPoint(val date: String, val value: Double)

/**
 * One course of the timetable, already normalized for the UI.
 * Times are local "HH:mm", dates ISO "yyyy-MM-dd".
 */
data class TimetableLesson(
    val subjectId: String?,
    val subjectName: String,
    val teacher: String? = null,
    val room: String? = null,
    val group: String? = null,
    /** ISO datetime start, e.g. "2026-10-08T08:00:00". */
    val startIso: String,
    /** ISO datetime end. */
    val endIso: String,
    val cancelled: Boolean = false,
    /** Detention, test label… free text under the subject. */
    val memo: String? = null,
) {
    /** "08:00". */
    val startTime: String get() = startIso.take(11 + 5).substring(11, 16)

    /** "09:00". */
    val endTime: String get() = endIso.take(16).substring(11, 16)

    /** ISO date of the course day. */
    val day: String get() = startIso.take(10)

    /** Duration in minutes — used to size the cards. */
    val durationMinutes: Int
        get() = runCatching {
            val fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME
            val s = java.time.LocalDateTime.parse(startIso, fmt)
            val e = java.time.LocalDateTime.parse(endIso, fmt)
            java.time.Duration.between(s, e).toMinutes().toInt()
        }.getOrDefault(60)
}

data class PronoteSession(
    /** Opaque token — never rendered, never logged. */
    val token: String,
    val expiresAt: Long,
    val schoolUrl: String,
    val schoolName: String,
    val connected: Boolean,
)

/** The whole school snapshot stored locally (cache-first rendering). */
data class SchoolSnapshot(
    val student: Student,
    val subjects: List<Subject>,
    val grades: List<Grade>,
    val subjectAverages: List<SubjectAverage>,
    val generalAverage: GeneralAverage?,
    val history: List<HistoryPoint>,
    val fetchedAt: Long,
)

/** Une période proposée par l'établissement (T1, T2, T3, Année…). */
data class PeriodOption(
    val id: String,
    val name: String,
)

/** Non-sensitive preferences (DataStore equivalent). */
data class Preferences(
    val autoSync: Boolean = true,
    val notifyNewGrades: Boolean = true,
    val notifyAverageChange: Boolean = true,
    val darkTheme: Boolean? = null, // null = follow system
    val dynamicColor: Boolean = true,
    val biometricLock: Boolean = false,
    /** Name shown after "Bonjour "; takes priority over the Pronote first name. */
    val displayName: String? = null,
    /** Ordre des blocs de l'accueil (identifiants de `core.HomeBlocks`). */
    val homeBlocks: List<String> = emptyList(),
    /** Réduit les animations, sans changer le reste de l'interface. */
    val reduceMotion: Boolean = false,
)

/**
 * Un devoir du cahier de textes, déjà aplati pour l'interface.
 *
 * [description] est le texte de la consigne, balises HTML retirées : Pronote
 * envoie du HTML riche que ni la liste ni le widget ne savent afficher.
 */
data class Homework(
    val id: String,
    val subjectName: String,
    val description: String,
    /** ISO date de remise (« 2026-11-30 »), vide si Pronote n'en donne pas. */
    val dueDate: String,
    /** ISO date à laquelle la consigne a été donnée. */
    val givenDate: String = "",
    val done: Boolean = false,
    /** Noms des pièces jointes, sans les télécharger. */
    val attachments: List<String> = emptyList(),
) {
    /** Tri stable : par date de remise, puis matière. */
    val sortKey: String get() = "${'$'}dueDate|${'$'}subjectName|${'$'}id"
}

enum class AttendanceKind { ABSENCE, DELAY }

/**
 * Une absence ou un retard, avec sa durée en minutes.
 *
 * Les deux viennent de la même liste Pronote (`listeAbsences`, code `G` 13 ou
 * 14) : les fusionner ici évite deux écrans pour une seule réalité — du temps
 * de classe manqué.
 */
data class AttendanceEntry(
    val id: String,
    val kind: AttendanceKind,
    /** ISO date (« 2026-11-30 »), vide si la date n'est pas lisible. */
    val date: String,
    val startTime: String? = null,
    val endTime: String? = null,
    val minutes: Int = 0,
    val justified: Boolean = false,
    val reason: String? = null,
) {
    val sortKey: String get() = "${'$'}date|${'$'}id"
}
