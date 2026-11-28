package com.pronote.clay.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBlocksTest {

    @Test
    fun `l'objectif est le dernier bloc par defaut`() {
        assertEquals(HomeBlocks.TARGET, HomeBlocks.DEFAULT.last())
        assertEquals(
            listOf(
                HomeBlocks.AVERAGE,
                HomeBlocks.HISTORY,
                HomeBlocks.ATTENDANCE,
                HomeBlocks.SUBJECTS,
                HomeBlocks.GRADES,
                HomeBlocks.TARGET,
            ),
            HomeBlocks.DEFAULT,
        )
    }

    @Test
    fun `un ordre stocke est complete et nettoye`() {
        // Un bloc inconnu (ancienne version), un doublon, et un bloc manquant.
        val stored = listOf(HomeBlocks.GRADES, "inconnu", HomeBlocks.GRADES, HomeBlocks.AVERAGE)
        val normalized = HomeBlocks.normalize(stored)

        assertEquals(HomeBlocks.DEFAULT.size, normalized.size)
        assertEquals(listOf(HomeBlocks.GRADES, HomeBlocks.AVERAGE), normalized.take(2))
        assertEquals(normalized.size, normalized.distinct().size)
    }

    @Test
    fun `deplacer un bloc prend la place de la cible`() {
        val moved = HomeBlocks.move(HomeBlocks.DEFAULT, HomeBlocks.TARGET, HomeBlocks.AVERAGE)
        assertEquals(HomeBlocks.TARGET, moved.first())
        assertEquals(HomeBlocks.DEFAULT.size, moved.size)

        val swapped = HomeBlocks.move(HomeBlocks.DEFAULT, HomeBlocks.AVERAGE, HomeBlocks.HISTORY)
        assertEquals(listOf(HomeBlocks.HISTORY, HomeBlocks.AVERAGE), swapped.take(2))
    }

    @Test
    fun `deplacer vers soi-meme ne change rien`() {
        assertEquals(
            HomeBlocks.DEFAULT,
            HomeBlocks.move(HomeBlocks.DEFAULT, HomeBlocks.GRADES, HomeBlocks.GRADES),
        )
    }
}
