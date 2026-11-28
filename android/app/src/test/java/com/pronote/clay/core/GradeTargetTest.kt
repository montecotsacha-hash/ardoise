package com.pronote.clay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Quelle note me faut-il ? » — le calcul doit être juste, sinon il ment. */
class GradeTargetTest {

    @Test
    fun `une moyenne de 12 sur un poids 3 demande 16 avec un coefficient 1 pour viser 13`() {
        // (13 × (3 + 1) − 12 × 3) / 1 = 16
        assertEquals(16.0, GradeTarget.required(12.0, 3.0, 1.0, 13.0)!!, 0.001)
    }

    @Test
    fun `un fort coefficient demande moins`() {
        val small = GradeTarget.required(12.0, 3.0, 1.0, 15.0)!!
        val big = GradeTarget.required(12.0, 3.0, 4.0, 15.0)!!
        assertTrue(big < small)
    }

    @Test
    fun `une cible déjà dépassée demande une note négative`() {
        val required = GradeTarget.required(16.0, 5.0, 1.0, 12.0)!!
        assertTrue(GradeTarget.isAlreadyReached(required))
        assertTrue(GradeTarget.isReachable(required))
    }

    @Test
    fun `une cible hors barème est signalée`() {
        val required = GradeTarget.required(8.0, 20.0, 1.0, 20.0)
        assertFalse(GradeTarget.isReachable(required))
    }

    @Test
    fun `sans coefficient il n'y a pas de réponse`() {
        assertNull(GradeTarget.required(12.0, 3.0, 0.0, 15.0))
    }

    @Test
    fun `le poids additionne les coefficients sans les négatifs`() {
        assertEquals(4.0, GradeTarget.weightOf(listOf(1.0, 2.0, -1.0, 1.0)), 0.001)
    }
}
