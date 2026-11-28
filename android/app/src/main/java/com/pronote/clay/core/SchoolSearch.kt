package com.pronote.clay.core

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Un établissement trouvé par la recherche par ville.
 *
 * [url] est l'adresse PRONOTE réelle de l'établissement, telle que la renvoie
 * l'annuaire d'Index Éducation : elle sert directement au connecteur, comme si
 * l'élève l'avait tapée à la main.
 */
data class SchoolSuggestion(
    val name: String,
    val url: String,
    val city: String? = null,
    val postalCode: String? = null,
) {
    /** Seconde ligne de la liste : « 44150 » ou « 44150 ANCENIS ». */
    val subtitle: String
        get() = listOfNotNull(postalCode, city).joinToString(" ").trim()
}

/** Centre-ville trouvé pour la ville saisie. */
data class CityPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Recherche d'établissement par ville, sans clé d'API.
 *
 * Deux services publics sont enchaînés :
 *  1. `api-adresse.data.gouv.fr` (Base Adresse Nationale) transforme « Ancenis »
 *     en coordonnées ;
 *  2. l'annuaire d'Index Éducation (`swie/geoloc.php`, celui qu'utilise
 *     l'application mobile PRONOTE) renvoie les établissements les plus proches
 *     de ces coordonnées, avec leur nom et leur URL PRONOTE.
 *
 * Aucune donnée personnelle ne part : seulement le nom de la ville. Le parsing
 * est isolé ici pour être testable sans réseau.
 */
object SchoolSearch {

    /** Ville → coordonnées. `type=municipality` évite les adresses de rue. */
    const val CITIES_ENDPOINT = "https://api-adresse.data.gouv.fr/search/"

    /** Annuaire PRONOTE : POST `data={"nomFonction":"geoLoc","lat":…,"long":…}`. */
    const val PRONOTE_ENDPOINT = "https://www.index-education.com/swie/geoloc.php"

    /** Un nom de ville plus court ne donne que du bruit. */
    const val MIN_QUERY_LENGTH = 2

    fun normalizeQuery(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /**
     * Normalisation pour comparer des noms : minuscules, sans accents, sans
     * ponctuation. « LYCÉE JOUVENCE » et « lycee jouvence » se comparent.
     */
    fun fold(text: String): String {
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        val out = StringBuilder(decomposed.length)
        var lastWasSpace = false
        for (character in decomposed) {
            // Les accents sont des marques combinantes : on les jette, « É »
            // redevient « e ».
            if (Character.getType(character) == Character.NON_SPACING_MARK.toInt()) continue
            if (character.isLetterOrDigit()) {
                out.append(character.lowercaseChar())
                lastWasSpace = false
            } else if (!lastWasSpace) {
                out.append(' ')
                lastWasSpace = true
            }
        }
        return out.toString().trim()
    }

    /**
     * Filtre une liste déjà obtenue sur le nom de l'établissement.
     *
     * L'annuaire ne sait chercher que par ville : une fois la liste de la ville
     * en main, taper « jouvence » doit la réduire sans nouvelle requête.
     */
    fun filterByName(schools: List<SchoolSuggestion>, query: String): List<SchoolSuggestion> {
        val needle = fold(query)
        if (needle.isEmpty()) return schools
        val words = needle.split(' ')
        return schools.filter { school ->
            val haystack = fold(school.name)
            words.all { haystack.contains(it) }
        }
    }

    /**
     * Extrait l'adresse PRONOTE d'un QR code (ou de tout texte collé).
     *
     * Le QR de PRONOTE contient l'adresse des espaces de l'établissement, avec
     * parfois du texte autour : on cherche l'URL et on la valide avec les mêmes
     * règles que la saisie manuelle.
     */
    fun extractSchoolUrl(raw: String): String? {
        val match = Regex("https?://[^\\s\"'<>]+").find(raw.trim()) ?: return null
        return LoginUrl.normalize(match.value)?.takeIf { LoginUrl.isValid(it) }
    }

    fun citiesUrl(query: String): String =
        CITIES_ENDPOINT + "?type=municipality&limit=1&q=" + encode(query)

    fun geoLocPayload(latitude: Double, longitude: Double): String =
        "data=" + encode(
            "{\"nomFonction\":\"geoLoc\",\"lat\":$latitude,\"long\":$longitude}",
        )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Première commune de la réponse de la Base Adresse Nationale. */
    fun parseCity(json: String): CityPoint? {
        val feature = runCatching { JSONObject(json).optJSONArray("features") }.getOrNull()
            ?.optJSONObject(0) ?: return null
        val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
            ?: return null
        if (coordinates.length() < 2) return null
        val latitude = coordinates.optDouble(1, Double.NaN)
        val longitude = coordinates.optDouble(0, Double.NaN)
        if (latitude.isNaN() || longitude.isNaN()) return null

        val properties = feature.optJSONObject("properties")
        // `city` porte la commune, `name` peut n'être que l'arrondissement.
        val label = properties?.optString("city").orEmpty()
            .ifBlank { properties?.optString("name").orEmpty() }
            .ifBlank { properties?.optString("label").orEmpty() }
        return CityPoint(label, latitude, longitude)
    }

    /**
     * Liste renvoyée par l'annuaire PRONOTE, doublons retirés.
     *
     * L'ordre du serveur est conservé : il va du plus proche au plus loin, ce
     * qui est exactement l'ordre attendu par quelqu'un qui tape sa ville.
     */
    fun parseSchools(json: String): List<SchoolSuggestion> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val name = item.optString("nomEtab").trim()
            val url = item.optString("url").trim().trimEnd('/')
            if (name.isEmpty() || url.isEmpty()) return@mapNotNull null
            if (!seen.add(url.lowercase())) return@mapNotNull null
            SchoolSuggestion(
                name = name,
                url = url,
                city = item.optString("ville").takeIf { it.isNotBlank() },
                postalCode = item.optString("cp").takeIf { it.isNotBlank() },
            )
        }
    }
}
