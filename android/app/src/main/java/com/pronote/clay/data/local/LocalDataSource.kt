package com.pronote.clay.data.local

import android.content.Context
import com.pronote.clay.core.AverageCalculator
import com.pronote.clay.core.Logger
import com.pronote.clay.domain.AttendanceEntry
import com.pronote.clay.domain.AttendanceKind
import com.pronote.clay.domain.AverageSource
import com.pronote.clay.domain.GeneralAverage
import com.pronote.clay.domain.Homework
import com.pronote.clay.domain.TimetableLesson
import com.pronote.clay.domain.PronoteSession
import com.pronote.clay.domain.SchoolSnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * LocalDataSource — the web/Room equivalent of the school cache.
 *
 * Every record is scoped to an account id. The UI is served from here instantly
 * (cache-first) and refreshed silently afterwards.
 *
 * Serialisation is hand-rolled JSON so the project stays dependency-light: the
 * data shapes are tiny and fully covered by the repository tests.
 */
class LocalDataSource(context: Context) {

    private val prefs = context.getSharedPreferences("pronote_school_cache", Context.MODE_PRIVATE)

    fun loadSnapshot(accountId: String, periodId: String? = null): SchoolSnapshot? {
        val raw = prefs.getString(snapshotKey(accountId, periodId), null) ?: return null
        return try {
            healGeneralAverage(decodeSnapshot(JSONObject(raw)))
        } catch (error: Throwable) {
            Logger.warn("Local snapshot unreadable")
            null
        }
    }

    /**
     * Older cached snapshots can lack the general average while grades exist
     * (hidden Pronote average, partial sync). Rebuilding it here heals every
     * consumer at once — the home screen and the widgets read through this.
     */
    private fun healGeneralAverage(snapshot: SchoolSnapshot): SchoolSnapshot {
        if (snapshot.generalAverage != null) return snapshot
        val usableAverages = snapshot.subjectAverages.filter { it.value > 0.0 }
        val fromSubjects = if (usableAverages.isEmpty()) {
            null
        } else {
            AverageCalculator.generalAverageFromSubjects(snapshot.subjects, usableAverages)
        }
        val fromGrades = AverageCalculator.generalAverageFromGrades(snapshot.grades)
        val value = fromSubjects ?: fromGrades ?: return snapshot
        return snapshot.copy(
            generalAverage = GeneralAverage(
                value = value,
                period = snapshot.subjectAverages.firstOrNull()?.period ?: "",
                source = AverageSource.CALCULATED,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Enregistre un instantané. [periodId] non nul le range aussi par
     * trimestre, ce qui permet de revenir sur un trimestre passé sans réseau.
     */
    fun saveSnapshot(accountId: String, snapshot: SchoolSnapshot, periodId: String? = null) {
        try {
            prefs.edit()
                .putString(snapshotKey(accountId), encodeSnapshot(snapshot).toString())
                .apply()
            if (periodId != null) {
                prefs.edit()
                    .putString(snapshotKey(accountId, periodId), encodeSnapshot(snapshot).toString())
                    .apply()
            }
        }

         catch (error: Throwable) {
            Logger.warn("Local snapshot write failed")
        }
    }

    /** Tous les instantanés connus de ce compte, période courante comprise. */
    fun loadSnapshots(accountId: String): List<SchoolSnapshot> {
        val prefix = "snapshot.$accountId"
        return prefs.all.entries
            .filter { (key, value) -> key.startsWith(prefix) && value is String }
            .mapNotNull { (_, value) ->
                runCatching { decodeSnapshot(JSONObject(value as String)) }.getOrNull()
            }
    }

    /** Périodes proposées par l'établissement (T1, T2, T3…), pour le sélecteur. */
    fun savePeriods(accountId: String, periods: List<com.pronote.clay.domain.PeriodOption>) {
        val array = JSONArray()
        periods.forEach { period ->
            array.put(JSONObject().put("id", period.id).put("name", period.name))
        }
        prefs.edit().putString("periods.$accountId", array.toString()).apply()
    }

    fun loadPeriods(accountId: String): List<com.pronote.clay.domain.PeriodOption> {
        val raw = prefs.getString("periods.$accountId", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                val name = item.optString("name")
                if (id.isNullOrBlank() || name.isNullOrBlank()) null
                else com.pronote.clay.domain.PeriodOption(id, name)
            }
        }.getOrDefault(emptyList())
    }

    fun saveSelectedPeriod(accountId: String, periodId: String) {
        prefs.edit().putString("period.$accountId", periodId).apply()
    }

    fun loadSelectedPeriod(accountId: String): String? =
        prefs.getString("period.$accountId", null)?.takeIf { it.isNotBlank() }

    fun clearSnapshot(accountId: String) {
        prefs.edit().remove(snapshotKey(accountId)).apply()
    }

    /** "Supprimer mes données" — wipes every trace of the account on this device. */
    fun clearAccount(accountId: String) {
        val prefix = "snapshot.$accountId"
        val keys = prefs.all.keys.filter {
            it.startsWith(prefix) ||
                it == "periods.$accountId" ||
                it == "period.$accountId" ||
                it == "homework.$accountId" ||
                it == "attendance.$accountId" ||
                it == "week.$accountId"
        }
        prefs.edit().apply {
            keys.forEach { remove(it) }
        }.apply()
    }

    /** Semaine d'emploi du temps telle qu'elle a été mémorisée. */
    data class CachedWeek(val weekStartIso: String, val lessons: List<TimetableLesson>)

    private fun snapshotKey(accountId: String, periodId: String? = null) =
        if (periodId == null) "snapshot.$accountId" else "snapshot.$accountId.$periodId"

    // ---- serialisation ----------------------------------------------------

    private fun encodeSnapshot(s: SchoolSnapshot): JSONObject = JSONObject().apply {
        put("student", JSONObject().apply {
            put("id", s.student.id)
            put("firstName", s.student.firstName)
            put("lastName", s.student.lastName)
            put("className", s.student.className)
            put("schoolName", s.student.schoolName)
        })
        put("subjects", JSONArray().apply {
            s.subjects.forEach { subject ->
                put(JSONObject().apply {
                    put("id", subject.id)
                    put("name", subject.name)
                    put("shortName", subject.shortName)
                    put("teacher", subject.teacher)
                    put("coefficient", subject.coefficient)
                    put("colorIndex", subject.colorIndex)
                })
            }
        })
        put("grades", JSONArray().apply {
            s.grades.forEach { grade ->
                put(JSONObject().apply {
                    put("id", grade.id)
                    put("subjectId", grade.subjectId)
                    put("title", grade.title)
                    put("value", grade.value)
                    put("maxValue", grade.maxValue)
                    put("coefficient", grade.coefficient)
                    put("date", grade.date)
                    put("comment", grade.comment)
                    put("counts", grade.countsTowardAverage)
                })
            }
        })
        put("averages", JSONArray().apply {
            s.subjectAverages.forEach { avg ->
                put(JSONObject().apply {
                    put("subjectId", avg.subjectId)
                    put("value", avg.value)
                    put("period", avg.period)
                    put("source", avg.source.name)
                })
            }
        })
        s.generalAverage?.let { avg ->
            put("general", JSONObject().apply {
                put("value", avg.value)
                put("period", avg.period)
                put("source", avg.source.name)
                put("updatedAt", avg.updatedAt)
            })
        }
        put("history", JSONArray().apply {
            s.history.forEach { point ->
                put(JSONObject().apply {
                    put("date", point.date)
                    put("value", point.value)
                })
            }
        })
        put("fetchedAt", s.fetchedAt)
    }

    private fun decodeSnapshot(json: JSONObject): SchoolSnapshot {
        val studentJson = json.getJSONObject("student")
        val subjectsJson = json.optJSONArray("subjects") ?: JSONArray()
        val gradesJson = json.optJSONArray("grades") ?: JSONArray()
        val averagesJson = json.optJSONArray("averages") ?: JSONArray()
        val historyJson = json.optJSONArray("history") ?: JSONArray()

        return SchoolSnapshot(
            student = com.pronote.clay.domain.Student(
                id = studentJson.getString("id"),
                firstName = studentJson.getString("firstName"),
                lastName = studentJson.getString("lastName"),
                className = studentJson.optString("className"),
                schoolName = studentJson.optString("schoolName"),
            ),
            subjects = (0 until subjectsJson.length()).map { i ->
                val o = subjectsJson.getJSONObject(i)
                com.pronote.clay.domain.Subject(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    shortName = o.optString("shortName"),
                    teacher = o.optString("teacher"),
                    coefficient = o.optDouble("coefficient", 1.0),
                    colorIndex = o.optInt("colorIndex", 0),
                )
            },
            grades = (0 until gradesJson.length()).map { i ->
                val o = gradesJson.getJSONObject(i)
                com.pronote.clay.domain.Grade(
                    id = o.getString("id"),
                    subjectId = o.getString("subjectId"),
                    title = o.optString("title"),
                    value = o.optDouble("value", 0.0),
                    maxValue = o.optDouble("maxValue", 20.0),
                    coefficient = o.optDouble("coefficient", 1.0),
                    date = o.optString("date"),
                    comment = o.optString("comment").takeIf { it.isNotBlank() && it != "null" },
                    countsTowardAverage = o.optBoolean("counts", true),
                )
            },
            subjectAverages = (0 until averagesJson.length()).map { i ->
                val o = averagesJson.getJSONObject(i)
                com.pronote.clay.domain.SubjectAverage(
                    subjectId = o.getString("subjectId"),
                    value = o.optDouble("value", 0.0),
                    period = o.optString("period"),
                    source = com.pronote.clay.domain.AverageSource.valueOf(
                        o.optString("source", "CALCULATED"),
                    ),
                )
            },
            generalAverage = if (json.has("general")) {
                val o = json.getJSONObject("general")
                com.pronote.clay.domain.GeneralAverage(
                    value = o.optDouble("value", 0.0),
                    period = o.optString("period"),
                    source = com.pronote.clay.domain.AverageSource.valueOf(
                        o.optString("source", "CALCULATED"),
                    ),
                    updatedAt = o.optLong("updatedAt"),
                )
            } else null,
            history = (0 until historyJson.length()).map { i ->
                val o = historyJson.getJSONObject(i)
                com.pronote.clay.domain.HistoryPoint(
                    date = o.getString("date"),
                    value = o.optDouble("value", 0.0),
                )
            },
            fetchedAt = json.optLong("fetchedAt"),
        )
    }

    // ---- travail, vie scolaire, semaine mémorisée -------------------------

    /**
     * Cahier de textes mémorisé.
     *
     * Il sert deux fois : hors-ligne, et au widget « Aujourd'hui », qui n'a pas
     * le droit de lancer une requête réseau depuis son rendu.
     */
    fun saveHomework(accountId: String, items: List<Homework>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("subject", item.subjectName)
                put("description", item.description)
                put("due", item.dueDate)
                put("given", item.givenDate)
                put("done", item.done)
                put("attachments", JSONArray(item.attachments))
            })
        }
        prefs.edit().putString("homework.$accountId", array.toString()).apply()
    }

    fun loadHomework(accountId: String): List<Homework> {
        val raw = prefs.getString("homework.$accountId", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                if (id.isBlank()) return@mapNotNull null
                Homework(
                    id = id,
                    subjectName = item.optString("subject"),
                    description = item.optString("description"),
                    dueDate = item.optString("due"),
                    givenDate = item.optString("given"),
                    done = item.optBoolean("done", false),
                    attachments = item.optJSONArray("attachments")?.let { names ->
                        (0 until names.length()).mapNotNull { names.optString(it).takeIf(String::isNotBlank) }
                    }.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveAttendance(accountId: String, items: List<AttendanceEntry>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("kind", item.kind.name)
                put("date", item.date)
                put("start", item.startTime)
                put("end", item.endTime)
                put("minutes", item.minutes)
                put("justified", item.justified)
                put("reason", item.reason)
            })
        }
        prefs.edit().putString("attendance.$accountId", array.toString()).apply()
    }

    fun loadAttendance(accountId: String): List<AttendanceEntry> {
        val raw = prefs.getString("attendance.$accountId", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id")
                if (id.isBlank()) return@mapNotNull null
                AttendanceEntry(
                    id = id,
                    kind = runCatching {
                        AttendanceKind.valueOf(item.optString("kind", "ABSENCE"))
                    }.getOrDefault(AttendanceKind.ABSENCE),
                    date = item.optString("date"),
                    startTime = item.optString("start").takeIf { it.isNotBlank() },
                    endTime = item.optString("end").takeIf { it.isNotBlank() },
                    minutes = item.optInt("minutes", 0),
                    justified = item.optBoolean("justified", false),
                    reason = item.optString("reason").takeIf { it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Dernière semaine d'emploi du temps vue, avec son lundi ISO. */
    fun saveWeek(accountId: String, weekStartIso: String, lessons: List<TimetableLesson>) {
        val json = JSONObject().apply {
            put("weekStart", weekStartIso)
            put("lessons", JSONArray().apply {
                lessons.forEach { lesson ->
                    put(JSONObject().apply {
                        put("subjectId", lesson.subjectId)
                        put("subjectName", lesson.subjectName)
                        put("teacher", lesson.teacher)
                        put("room", lesson.room)
                        put("group", lesson.group)
                        put("start", lesson.startIso)
                        put("end", lesson.endIso)
                        put("cancelled", lesson.cancelled)
                        put("memo", lesson.memo)
                    })
                }
            })
        }
        prefs.edit().putString("week.$accountId", json.toString()).apply()
    }

    fun loadWeek(accountId: String): CachedWeek? {
        val raw = prefs.getString("week.$accountId", null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val weekStart = json.optString("weekStart")
            val array = json.optJSONArray("lessons") ?: JSONArray()
            val lessons = (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val start = item.optString("start")
                if (start.length < 16) return@mapNotNull null
                TimetableLesson(
                    subjectId = item.optString("subjectId").takeIf { it.isNotBlank() },
                    subjectName = item.optString("subjectName").ifBlank { "Cours" },
                    teacher = item.optString("teacher").takeIf { it.isNotBlank() },
                    room = item.optString("room").takeIf { it.isNotBlank() },
                    group = item.optString("group").takeIf { it.isNotBlank() },
                    startIso = start,
                    endIso = item.optString("end").ifBlank { start },
                    cancelled = item.optBoolean("cancelled", false),
                    memo = item.optString("memo").takeIf { it.isNotBlank() },
                )
            }
            CachedWeek(weekStart, lessons)
        }.getOrNull()
    }

    // ---- sauvegarde -------------------------------------------------------

    /**
     * Données du compte pour une sauvegarde.
     *
     * Les valeurs sont reprises telles quelles (JSON déjà validé à la lecture)
     * et les clés de session sont volontairement absentes : une sauvegarde ne
     * contient ni identifiant, ni jeton, ni adresse d'établissement.
     */
    fun exportAccount(accountId: String): JSONObject = JSONObject().apply {
        put("snapshot", prefs.getString(snapshotKey(accountId), null))
        put("periods", prefs.getString("periods.$accountId", null))
        put("period", prefs.getString("period.$accountId", null))
        put("homework", prefs.getString("homework.$accountId", null))
        put("attendance", prefs.getString("attendance.$accountId", null))
        put("week", prefs.getString("week.$accountId", null))
    }

    /**
     * Réécrit les données du compte depuis une sauvegarde.
     *
     * Une valeur absente laisse le cache existant intact : restaurer une
     * sauvegarde d'un appareil qui n'avait pas encore d'emploi du temps ne
     * doit pas effacer celui déjà présent.
     */
    fun importAccount(accountId: String, data: JSONObject) {
        val targets = listOf(
            "snapshot" to snapshotKey(accountId),
            "periods" to "periods.$accountId",
            "period" to "period.$accountId",
            "homework" to "homework.$accountId",
            "attendance" to "attendance.$accountId",
            "week" to "week.$accountId",
        )
        val editor = prefs.edit()
        for ((source, target) in targets) {
            val value = data.optString(source, "")
            if (value.isNotBlank()) editor.putString(target, value)
        }
        editor.apply()
    }

    // ---- session (non-secret part only) -----------------------------------

    fun saveSessionMeta(accountId: String, session: PronoteSession) {
        prefs.edit()
            .putString("session.$accountId.url", session.schoolUrl)
            .putString("session.$accountId.school", session.schoolName)
            .putLong("session.$accountId.expires", session.expiresAt)
            .apply()
    }

    fun sessionMeta(accountId: String): PronoteSession? {
        val url = prefs.getString("session.$accountId.url", null) ?: return null
        return PronoteSession(
            token = "", // the token itself only ever lives in the encrypted vault
            expiresAt = prefs.getLong("session.$accountId.expires", 0L),
            schoolUrl = url,
            schoolName = prefs.getString("session.$accountId.school", "") ?: "",
            connected = true,
        )
    }

    fun clearSessionMeta(accountId: String) {
        prefs.edit()
            .remove("session.$accountId.url")
            .remove("session.$accountId.school")
            .remove("session.$accountId.expires")
            .apply()
    }
}
