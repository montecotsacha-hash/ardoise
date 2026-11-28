package com.pronote.clay.data.remote

import com.pronote.clay.core.LoginUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ce que le diagnostic a trouvé. */
enum class DiagnosisKind {
    /** La page répond et affiche une version de PRONOTE. */
    READY,

    /** L'adresse IP de l'appareil est temporairement bloquée par l'établissement. */
    SUSPENDED_IP,

    /** Le serveur est en maintenance. */
    BUSY,

    /** L'adresse ne correspond à aucun PRONOTE. */
    NOT_FOUND,

    /** Le serveur répond, mais pas la page mobile attendue (souvent un ENT). */
    ENT_OR_WRONG_PAGE,

    /** Rien au bout du fil : nom d'hôte faux, pas de réseau, port fermé. */
    UNREACHABLE,
}

data class Diagnosis(
    val kind: DiagnosisKind,
    val message: String,
    val hint: String? = null,
    val pronoteVersion: String? = null,
)

/**
 * Diagnostic « ça ne se connecte pas ».
 *
 * Une seule requête, celle que fait déjà le connecteur : la page mobile de
 * l'établissement. Elle distingue les causes que l'utilisateur peut traiter
 * (adresse fausse, IP suspendue, maintenance) de celles qui ne le concernent
 * pas. Aucun identifiant n'est envoyé : c'est une simple lecture de page.
 */
class ConnectionDoctor(
    private val timeoutMs: Int = 15_000,
    private val userAgent: String = PRONOTE_UA,
) {

    suspend fun check(rawUrl: String): Diagnosis = withContext(Dispatchers.IO) {
        val normalized = LoginUrl.normalize(rawUrl)
            ?: return@withContext Diagnosis(
                DiagnosisKind.UNREACHABLE,
                "Cette adresse n'est pas une adresse web.",
                "Exemple : https://0123456a.index-education.net/pronote/",
            )
        if (!LoginUrl.isValid(normalized)) {
            return@withContext Diagnosis(
                DiagnosisKind.UNREACHABLE,
                "Adresse incomplète.",
                "Exemple : https://0123456a.index-education.net/pronote/",
            )
        }

        val base = try {
            PronoteSession.cleanUrl(normalized)
        } catch (error: Throwable) {
            return@withContext Diagnosis(
                DiagnosisKind.UNREACHABLE,
                "Adresse illisible.",
                "Vérifie l'adresse copiée depuis Pronote.",
            )
        }

        try {
            val connection = URL("$base/mobile.eleve.html?fd=1&login=true").openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent)
            try {
                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader(Charsets.UTF_8).readText()
                } else {
                    ""
                }
                classify(code, body)
            } finally {
                connection.disconnect()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Diagnosis(
                DiagnosisKind.UNREACHABLE,
                "Le serveur de ton établissement ne répond pas.",
                "Vérifie l'adresse, ta connexion, puis réessaie dans quelques minutes.",
            )
        }
    }

    companion object {
        /** Le même agent que le connecteur : sans lui, certaines pages refusent. */
        const val PRONOTE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 19_0 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Mobile/15E148 PRONOTE Mobile APP Version/2.0.11"

        // Certaines versions portent quatre nombres (2026.1.3.1) : on garde la
        // version telle que le serveur l'affiche.
        private val VERSION_PATTERN = Regex(">PRONOTE (\\d+\\.\\d+\\.\\d+(?:\\.\\d+)?)")

        /** Classification pure : testable sans réseau. */
        fun classify(statusCode: Int, body: String): Diagnosis = when {
            body.contains("IP est provisoirement suspendue") -> Diagnosis(
                DiagnosisKind.SUSPENDED_IP,
                "Pronote bloque les connexions depuis cette adresse.",
                "Trop de tentatives en peu de temps : attends environ une heure, puis réessaie.",
            )
            body.contains("momentanément indisponible") || body.contains("site est momentanément") ->
                Diagnosis(
                    DiagnosisKind.BUSY,
                    "Le serveur est en maintenance.",
                    "Ce n'est pas de ton côté : réessaie plus tard.",
                )
            VERSION_PATTERN.containsMatchIn(body) -> Diagnosis(
                DiagnosisKind.READY,
                "L'établissement répond.",
                "L'adresse est bonne : tu peux saisir tes identifiants.",
                pronoteVersion = VERSION_PATTERN.find(body)?.groupValues?.get(1),
            )
            statusCode == 404 -> Diagnosis(
                DiagnosisKind.NOT_FOUND,
                "Aucun Pronote à cette adresse.",
                "Vérifie les chiffres et lettres du début de l'adresse (le RNE de l'établissement).",
            )
            statusCode in 200..299 -> Diagnosis(
                DiagnosisKind.ENT_OR_WRONG_PAGE,
                "La page répond, mais ce n'est pas la page mobile de Pronote.",
                "Souvent le signe d'un accès par un ENT : reprends l'adresse donnée par ton établissement.",
            )
            else -> Diagnosis(
                DiagnosisKind.NOT_FOUND,
                "Le serveur a refusé la requête (HTTP $statusCode).",
                "Vérifie l'adresse de l'établissement.",
            )
        }
    }
}
