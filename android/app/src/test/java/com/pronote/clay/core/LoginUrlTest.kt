package com.pronote.clay.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adresse Pronote : les élèves la tapent sans schéma. Le bouton de connexion
 * restait alors grisé sans explication — ces tests figent la normalisation.
 */
class LoginUrlTest {

    @Test
    fun `ajoute https a une adresse tapee sans schema`() {
        assertEquals(
            "https://demo.index-education.net/pronote/",
            LoginUrl.normalize("demo.index-education.net/pronote/"),
        )
    }

    @Test
    fun `conserve une adresse https et nettoie les espaces`() {
        assertEquals(
            "https://demo.index-education.net/pronote/",
            LoginUrl.normalize("  https://demo.index-education.net/pronote/  "),
        )
    }

    @Test
    fun `remonte http en https`() {
        assertEquals(
            "https://demo.index-education.net/pronote/",
            LoginUrl.normalize("http://demo.index-education.net/pronote/"),
        )
    }

    @Test
    fun `refuse un autre schema et une saisie vide`() {
        assertNull(LoginUrl.normalize("ftp://demo.index-education.net/pronote/"))
        assertNull(LoginUrl.normalize(""))
        assertNull(LoginUrl.normalize("   "))
        assertNull(LoginUrl.normalize("demo index education net"))
    }

    @Test
    fun `le bouton n'est propose que pour une adresse exploitable`() {
        assertTrue(LoginUrl.isValid("demo.index-education.net/pronote/"))
        assertTrue(LoginUrl.isValid("https://demo.index-education.net/pronote/"))
        assertFalse(LoginUrl.isValid("demo"))
        assertFalse(LoginUrl.isValid("ftp://demo.index-education.net"))
        assertFalse(LoginUrl.isValid(""))
    }
}
