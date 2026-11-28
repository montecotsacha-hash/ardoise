package com.pronote.clay.core

/**
 * Les blocs réordonnables de la page d'accueil.
 *
 * L'ordre est mémorisé par identifiant, jamais par position : un bloc ajouté
 * dans une version ultérieure apparaît en fin de liste au lieu de disparaître,
 * et un identifiant inconnu (venu d'une version antérieure) est ignoré.
 */
object HomeBlocks {
    const val AVERAGE = "average"
    const val HISTORY = "history"
    const val ATTENDANCE = "attendance"
    const val SUBJECTS = "subjects"
    const val GRADES = "grades"
    const val TARGET = "target"

    /** Ordre par défaut : l'objectif de moyenne ferme la page. */
    val DEFAULT = listOf(AVERAGE, HISTORY, ATTENDANCE, SUBJECTS, GRADES, TARGET)

    /** Ordre stocké rendu utilisable : identifiants connus, sans doublon, tous présents. */
    fun normalize(stored: List<String>): List<String> {
        if (stored.isEmpty()) return DEFAULT
        val known = DEFAULT.toSet()
        val kept = stored.filter { it in known }.distinct()
        return kept + DEFAULT.filter { it !in kept }
    }

    /**
     * Déplace [id] à la place de [targetId] (appui long puis glissement).
     * Retourne l'ordre inchangé quand l'un des deux blocs n'existe pas.
     */
    fun move(order: List<String>, id: String, targetId: String): List<String> {
        val current = normalize(order)
        val from = current.indexOf(id)
        val to = current.indexOf(targetId)
        if (from < 0 || to < 0 || from == to) return current
        val next = current.toMutableList()
        next.removeAt(from)
        next.add(to.coerceIn(0, next.size), id)
        return next
    }
}
