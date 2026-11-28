package com.pronote.clay.data.remote

import org.json.JSONArray
import org.json.JSONObject

// ------------------------------- cahier de textes et vie scolaire
//
// Les deux réponses arrivent sous la même forme que l'emploi du temps : des
// listes tantôt directes, tantôt enveloppées dans `V`, des dates tantôt en
// tableau `[année, mois, jour, h, min]`, tantôt en chaîne `dd/MM/yyyy`. Rien
// ici ne suppose une seule forme : un serveur qui change d'emballage ne doit
// pas vider l'écran.

/**
 * Analyse le `ListeTravauxAFaire` d'une réponse `PageCahierDeTexte` (onglet 88).
 *
 * Clés du protocole mobile : `N`, `Matiere.V.L`, `descriptif.V` (HTML),
 * `PourLe.V` / `DonneLe.V` (`dd/MM/yyyy`), `TAFFait`, `ListePieceJointe.V`.
 * Un devoir sans texte mais avec une pièce jointe reste utile : il n'est
 * écarté que s'il n'a ni l'un ni l'autre.
 */
internal fun parseHomework(response: JSONObject): List<PronoteHomeworkDto> =
    pronoteList(response, "ListeTravauxAFaire").mapNotNull { item ->
        val matiere = pronoteObject(item, "Matiere")
        val description = pronoteHtmlToText(pronoteText(item, "descriptif").orEmpty())
        val pieces = pronoteList(item, "ListePieceJointe")
            .mapNotNull { piece -> piece.optString("L").takeIf { it.isNotBlank() } }

        if (description.isBlank() && pieces.isEmpty()) return@mapNotNull null

        PronoteHomeworkDto(
            id = item.optString("N").takeIf { it.isNotBlank() },
            matiere = matiere?.optString("N")?.takeIf { it.isNotBlank() },
            nomMatiere = matiere?.optString("L")?.takeIf { it.isNotBlank() }
                ?: pronoteText(item, "nomMatiere")?.takeIf { it.isNotBlank() },
            pourLe = parsePronoteDate(item.opt("PourLe")),
            donneLe = parsePronoteDate(item.opt("DonneLe")),
            description = description,
            fait = item.optBoolean("TAFFait", false),
            piecesJointes = pieces,
        )
    }.sortedWith(compareBy({ it.pourLe.orEmpty() }, { it.nomMatiere.orEmpty() }))

/**
 * Analyse le `listeAbsences` d'une réponse `PagePresence` (onglet 19).
 *
 * `G` 13 = absence, `G` 14 = retard ; le même tableau porte aussi les
 * punitions (`G` 41), volontairement ignorées. Une absence a un début et une
 * fin (`dateDebut`, `dateFin`) ; un retard a une date et une durée en minutes
 * (`date`, `duree`).
 */
internal fun parseAttendance(response: JSONObject): List<PronoteAttendanceDto> =
    pronoteList(response, "listeAbsences").mapNotNull { item ->
        when (item.optInt("G", -1)) {
            13 -> {
                val start = parsePronoteDateTime(item.opt("dateDebut"))
                val end = parsePronoteDateTime(item.opt("dateFin"))
                val minutes = if (start != null && end != null) {
                    minutesBetween(start, end)
                } else {
                    parseHours(pronoteText(item, "NbrHeures"))
                }
                PronoteAttendanceDto(
                    id = item.optString("N").takeIf { it.isNotBlank() },
                    kind = "absence",
                    debut = start,
                    fin = end,
                    minutes = minutes,
                    justifie = item.optBoolean("justifie", false),
                    motif = pronoteReasons(item),
                )
            }

            14 -> PronoteAttendanceDto(
                id = item.optString("N").takeIf { it.isNotBlank() },
                kind = "delay",
                debut = parsePronoteDateTime(item.opt("date")),
                fin = null,
                minutes = item.optInt("duree", 0),
                justifie = item.optBoolean("justifie", false),
                motif = pronoteText(item, "justification")?.takeIf { it.isNotBlank() }
                    ?: pronoteReasons(item),
            )

            else -> null
        }
    }

// ------------------------------------------------ lecture tolérante

/** Liste Pronote : tableau direct ou objet `{ "V": [ … ] }`. */
internal fun pronoteList(parent: JSONObject, key: String): List<JSONObject> {
    val array = parent.optJSONArray(key)
        ?: parent.optJSONObject(key)?.optJSONArray("V")
        ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

/** Valeur d'une clé Pronote, `V` retiré quand il emballe la valeur. */
internal fun pronoteValue(parent: JSONObject, key: String): Any? =
    unwrapPronote(parent.opt(key))

private fun unwrapPronote(value: Any?): Any? = when (value) {
    is JSONObject -> if (value.has("V")) unwrapPronote(value.opt("V")) else value
    is JSONArray -> value
    else -> value
}

/** Texte d'une clé Pronote (`{ "V": "…" }` ou `{ "V": { "L": "…" } }`). */
internal fun pronoteText(parent: JSONObject, key: String): String? =
    when (val value = unwrapPronote(parent.opt(key))) {
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is JSONObject -> value.optString("L").trim().takeIf { it.isNotEmpty() }
        is Number -> value.toString()
        else -> null
    }

/** Objet d'une clé Pronote, `V` retiré. */
internal fun pronoteObject(parent: JSONObject, key: String): JSONObject? =
    unwrapPronote(parent.opt(key)) as? JSONObject

/** Motifs d'une absence ou d'un retard, dédoublonnés. */
private fun pronoteReasons(item: JSONObject): String? =
    pronoteList(item, "listeMotifs")
        .mapNotNull { motif -> motif.optString("L").takeIf { it.isNotBlank() } }
        .distinct()
        .joinToString(", ")
        .takeIf { it.isNotBlank() }

/**
 * Date Pronote -> ISO « yyyy-MM-dd ». Accepte le tableau `[y, m, d, …]`
 * (mois 1 à 12, la forme des réponses), `dd/MM/yyyy`, `yyyy-MM-dd` et un
 * date-heure dont on garde le jour.
 */
internal fun parsePronoteDate(value: Any?): String? = when (val raw = unwrapPronote(value)) {
    is JSONArray -> {
        val year = raw.optInt(0, 0)
        val month = raw.optInt(1, 0)
        val day = raw.optInt(2, 0)
        runCatching { java.time.LocalDate.of(year, month, day).toString() }.getOrNull()
    }

    is String -> {
        val text = raw.trim()
        val french = Regex("^(\\d{2})/(\\d{2})/(\\d{4})").find(text)
        when {
            french != null -> runCatching {
                java.time.LocalDate.of(
                    french.groupValues[3].toInt(),
                    french.groupValues[2].toInt(),
                    french.groupValues[1].toInt(),
                ).toString()
            }.getOrNull()

            text.length >= 10 -> runCatching {
                java.time.LocalDate.parse(text.take(10)).toString()
            }.getOrNull()

            else -> null
        }
    }

    else -> null
}

/**
 * Date-heure Pronote -> ISO « yyyy-MM-ddTHH:mm ». Même tolérance que
 * [parsePronoteDate], avec `dd/MM/yyyy HH:mm[:ss]` et l'ISO.
 */
internal fun parsePronoteDateTime(value: Any?): String? = when (val raw = unwrapPronote(value)) {
    is JSONArray -> {
        if (raw.length() < 3) {
            null
        } else {
            runCatching {
                java.time.LocalDateTime.of(
                    raw.optInt(0), raw.optInt(1), raw.optInt(2),
                    raw.optInt(3, 0), raw.optInt(4, 0),
                ).withSecond(0).toString()
            }.getOrNull()
        }
    }

    is String -> {
        val text = raw.trim()
        runCatching {
            java.time.LocalDateTime.parse(text.replace(' ', 'T').take(19)).withSecond(0).toString()
        }.getOrNull() ?: runCatching {
            java.time.LocalDateTime.parse(
                text,
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm[:ss]"),
            ).withSecond(0).toString()
        }.getOrNull()
    }

    else -> null
}

/** Durée en minutes entre deux dates ISO déjà normalisées. */
private fun minutesBetween(startIso: String, endIso: String): Int = runCatching {
    val start = java.time.LocalDateTime.parse(startIso)
    val end = java.time.LocalDateTime.parse(endIso)
    java.time.Duration.between(start, end).toMinutes().toInt().coerceAtLeast(0)
}.getOrDefault(0)

/** « 3h », « 3h30 », « 1,5 » (heures décimales) -> minutes. */
private fun parseHours(raw: String?): Int {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return 0
    val match = Regex("^(\\d+)\\s*[hH]\\s*(\\d{1,2})?").find(text)
    if (match != null) {
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        return hours * 60 + minutes
    }
    val decimal = text.replace(',', '.').toDoubleOrNull() ?: return 0
    return (decimal * 60).toInt()
}

/**
 * HTML Pronote -> texte simple.
 *
 * Les consignes contiennent des paragraphes, des listes et des entités : on
 * garde les retours à la ligne utiles (un paragraphe, une puce) et on jette le
 * reste du balisage. Sans ça, la liste affichait des `<p>` et des `&nbsp;`.
 */
internal fun pronoteHtmlToText(html: String): String {
    if (html.isBlank()) return ""
    var text = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|tr)>"), "\n")
        .replace(Regex("(?i)<li[^>]*>"), "• ")
        .replace(Regex("(?i)<(p|div|ul|ol|table)[^>]*>"), "")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")

    text = Regex("&#(\\d+);").replace(text) { match ->
        match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
    }

    return text.lines()
        .joinToString("\n") { line -> line.trim().replace(Regex("[ \\t]+"), " ") }
        .trim()
        .replace(Regex("\n{3,}"), "\n\n")
}
