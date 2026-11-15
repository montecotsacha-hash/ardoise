package com.pronote.clay.core

/**
 * Adresse Pronote saisie à la main.
 *
 * Les élèves tapent rarement le préfixe : « demo.index-education.net/pronote/ »
 * est une saisie normale, et l'écran exigeait `https://` sans le dire — le
 * bouton « Se connecter » restait grisé, sans message. On ajoute donc le schéma
 * manquant, on remonte `http://` en HTTPS, et on refuse explicitement les autres
 * schémas (`ftp://`, `intent://`…) plutôt que de les envoyer au serveur.
 */
object LoginUrl {

    private const val HTTPS = "https://"

    /** Adresse prête pour le connecteur, ou null si la saisie n'a pas de sens. */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Une espace au milieu d'une URL est toujours une faute de saisie.
        if (trimmed.any { it.isWhitespace() }) return null

        val lower = trimmed.lowercase()
        return when {
            lower.startsWith(HTTPS) -> trimmed
            lower.startsWith("http://") -> HTTPS + trimmed.substring("http://".length)
            trimmed.contains("://") -> null
            else -> HTTPS + trimmed
        }
    }

    /** Vrai si le bouton « Se connecter » peut être proposé. */
    fun isValid(raw: String): Boolean {
        val normalized = normalize(raw) ?: return false
        val host = normalized.removePrefix(HTTPS).trim('/')
        // Un nom d'hôte doit contenir un point : « a » n'est pas une adresse.
        return host.isNotEmpty() && !host.startsWith("/") && host.contains(".")
    }
}
