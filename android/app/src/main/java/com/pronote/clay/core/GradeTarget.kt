package com.pronote.clay.core

/**
 * « Quelle note me faut-il ? »
 *
 * Le calcul part de la moyenne affichée et du poids (somme des coefficients) des
 * notes qui la composent :
 *
 *     note = (cible × (poids + nouveau coefficient) − moyenne × poids) / nouveau coefficient
 *
 * Une note au-dessus du barème n'est pas une erreur de calcul : c'est la réponse
 * honnête (« ce n'est plus atteignable avec un seul contrôle »).
 */
object GradeTarget {

    /** Barème de référence : toutes les moyennes de l'app sont ramenées sur 20. */
    const val MAX = 20.0

    /**
     * Note nécessaire sur 20, ou null si les données ne permettent pas de
     * répondre (aucune note, coefficient nul).
     */
    fun required(
        currentAverage: Double,
        currentWeight: Double,
        newCoefficient: Double,
        target: Double,
    ): Double? {
        if (newCoefficient <= 0.0) return null
        if (currentWeight < 0.0) return null
        val total = target * (currentWeight + newCoefficient)
        val current = currentAverage * currentWeight
        return AverageCalculator.round2((total - current) / newCoefficient)
    }

    /** Vrai quand la note demandée tient dans le barème. */
    fun isReachable(required: Double?): Boolean = required != null && required <= MAX

    /** Vrai quand la moyenne cible est déjà atteinte, quel que soit le contrôle. */
    fun isAlreadyReached(required: Double?): Boolean = required != null && required <= 0.0

    /**
     * Poids des notes qui comptent dans la moyenne.
     *
     * Une note sur 10 de coefficient 1 pèse autant qu'une note sur 20 de
     * coefficient 1 : c'est le coefficient qui fait le poids, pas le barème.
     */
    fun weightOf(coefficients: List<Double>): Double =
        AverageCalculator.round2(coefficients.sumOf { it.coerceAtLeast(0.0) })
}
