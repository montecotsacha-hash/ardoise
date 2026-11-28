package com.pronote.clay.data.remote

import com.pronote.clay.core.PronoteError
import com.pronote.clay.core.PronoteResult
import com.pronote.clay.core.SchoolSearch
import com.pronote.clay.core.SchoolSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Annuaire des établissements PRONOTE, interrogé depuis l'appareil.
 *
 * Premier écran de l'application : au lieu de demander à l'élève l'adresse de
 * son lycée, il tape le nom de sa ville et choisit dans la liste. Les requêtes
 * partent directement, sans passer par un serveur Ardoise.
 *
 * Les dernières recherches sont mémorisées (en mémoire et dans un petit fichier
 * de cache) : une ville déjà cherchée se rouvre instantanément, y compris hors
 * ligne, sans rappeler deux annuaires publics.
 */
class SchoolDirectory(
    private val cacheFile: File? = null,
    private val online: () -> Boolean = { true },
    private val timeoutMs: Int = 15_000,
) {

    /** Clé = ville repliée (`SchoolSearch.fold`), valeur = établissements. */
    private val memory = java.util.concurrent.ConcurrentHashMap<String, List<SchoolSuggestion>>()

    init {
        loadPersisted()
    }

    /** Établissements déjà connus pour cette ville (vide si jamais cherchée). */
    fun cached(city: String): List<SchoolSuggestion> =
        memory[SchoolSearch.fold(city)].orEmpty()

    suspend fun search(city: String): PronoteResult<List<SchoolSuggestion>> =
        withContext(Dispatchers.IO) {
            val query = SchoolSearch.normalizeQuery(city)
            if (query.length < SchoolSearch.MIN_QUERY_LENGTH) {
                return@withContext PronoteResult.Success(emptyList())
            }
            if (!online()) {
                // Hors ligne : ce qui a déjà été trouvé reste consultable.
                val known = cached(query)
                return@withContext if (known.isNotEmpty()) {
                    PronoteResult.Success(known)
                } else {
                    PronoteResult.Failure(PronoteError.NetworkUnavailable)
                }
            }

            try {
                val point = SchoolSearch.parseCity(
                    httpGet(SchoolSearch.citiesUrl(query)),
                ) ?: return@withContext PronoteResult.Success(cached(query).also { remember(query, it) })

                val schools = SchoolSearch.parseSchools(
                    httpPostForm(
                        SchoolSearch.PRONOTE_ENDPOINT,
                        SchoolSearch.geoLocPayload(point.latitude, point.longitude),
                    ),
                )
                remember(query, schools)
                PronoteResult.Success(schools)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val known = cached(query)
                if (known.isNotEmpty()) {
                    PronoteResult.Success(known)
                } else {
                    PronoteResult.Failure(PronoteError.NetworkUnavailable)
                }
            }
        }

    // ---- cache -------------------------------------------------------------

    private fun remember(query: String, schools: List<SchoolSuggestion>) {
        if (schools.isEmpty()) return
        memory[SchoolSearch.fold(query)] = schools
        persist()
    }

    private fun persist() {
        val file = cacheFile ?: return
        runCatching {
            val root = JSONObject()
            // Les huit dernières villes suffisent : au-delà, on chasse la plus
            // ancienne pour que le fichier reste minuscule.
            memory.entries.toList().takeLast(MAX_CACHED_CITIES).forEach { (key, schools) ->
                root.put(
                    key,
                    JSONArray().apply {
                        schools.forEach { school ->
                            put(
                                JSONObject()
                                    .put("n", school.name)
                                    .put("u", school.url)
                                    .put("c", school.city)
                                    .put("p", school.postalCode),
                            )
                        }
                    },
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(root.toString())
        }
    }

    private fun loadPersisted() {
        val file = cacheFile ?: return
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            root.keys().forEach { key ->
                val array = root.optJSONArray(key) ?: return@forEach
                val schools = (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = item.optString("n")
                    val url = item.optString("u")
                    if (name.isBlank() || url.isBlank()) return@mapNotNull null
                    SchoolSuggestion(
                        name = name,
                        url = url,
                        city = item.optString("c").takeIf { it.isNotBlank() },
                        postalCode = item.optString("p").takeIf { it.isNotBlank() },
                    )
                }
                if (schools.isNotEmpty()) memory[key] = schools
            }
        }.onFailure {
            // Un cache illisible n'est pas une erreur : on repart d'une liste vide.
            runCatching { file.delete() }
        }
    }

    private fun httpGet(url: String): String = request(url) { connection ->
        connection.requestMethod = "GET"
    }

    private fun httpPostForm(url: String, body: String): String = request(url) { connection ->
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty(
            "Content-Type",
            "application/x-www-form-urlencoded;charset=UTF-8",
        )
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    private fun request(url: String, configure: (HttpURLConnection) -> Unit): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json")
        try {
            configure(connection)
            val code = connection.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** Un agent identifiable : les annuaires publics aiment savoir qui appelle. */
        const val USER_AGENT = "Ardoise/Android (+https://ardoise.freebuff.page)"

        const val MAX_CACHED_CITIES = 8
    }
}
