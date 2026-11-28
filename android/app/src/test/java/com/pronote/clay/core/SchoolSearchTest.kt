package com.pronote.clay.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'annuaire est la première chose que voit un nouvel élève : une réponse
 * inattendue doit donner une liste vide, jamais un plantage.
 */
@RunWith(AndroidJUnit4::class)
class SchoolSearchTest {

    private val cityJson = """
        {"type":"FeatureCollection","features":[{"type":"Feature",
        "geometry":{"type":"Point","coordinates":[-1.197594,47.385541]},
        "properties":{"label":"Ancenis-Saint-Géréon","name":"Ancenis-Saint-Géréon",
        "city":"Ancenis-Saint-Géréon","postcode":"44150","type":"municipality"}}]}
    """.trimIndent()

    private val schoolsJson = """
        [{"url":"https:\/\/0440033T.index-education.net\/pronote",
          "nomEtab":"LYCEE GENERAL ET TECHNOLOGIQUE JOUVENCE","lat":"47.36","long":"-1.18","cp":"44150"},
         {"url":"https:\/\/0440033T.index-education.net\/pronote\/",
          "nomEtab":"DOUBLON","lat":"47.36","long":"-1.18","cp":"44150"},
         {"url":"","nomEtab":"SANS ADRESSE","cp":"44150"}]
    """.trimIndent()

    @Test
    fun `une ville donne ses coordonnees`() {
        val point = SchoolSearch.parseCity(cityJson)
        assertEquals("Ancenis-Saint-Géréon", point?.name)
        assertEquals(47.385541, point!!.latitude, 0.000001)
        assertEquals(-1.197594, point.longitude, 0.000001)
    }

    @Test
    fun `une reponse inattendue ne donne aucune ville`() {
        assertNull(SchoolSearch.parseCity("{}"))
        assertNull(SchoolSearch.parseCity("pas du json"))
        assertNull(SchoolSearch.parseCity("{\"features\":[]}"))
    }

    @Test
    fun `la liste garde l'ordre du serveur et retire les doublons`() {
        val schools = SchoolSearch.parseSchools(schoolsJson)
        assertEquals(1, schools.size)
        assertEquals("LYCEE GENERAL ET TECHNOLOGIQUE JOUVENCE", schools.first().name)
        assertEquals("https://0440033T.index-education.net/pronote", schools.first().url)
        assertEquals("44150", schools.first().subtitle)
    }

    @Test
    fun `une reponse illisible donne une liste vide`() {
        assertTrue(SchoolSearch.parseSchools("<html>busy</html>").isEmpty())
    }

    @Test
    fun `la requete encode la ville et le point`() {
        assertTrue(
            SchoolSearch.citiesUrl("Saint-Quay Perros")
                .endsWith("q=Saint-Quay+Perros"),
        )
        val payload = SchoolSearch.geoLocPayload(47.3667, -1.1833)
        assertTrue(payload.startsWith("data="))
        assertTrue(payload.contains("%22nomFonction%22"))
    }

    @Test
    fun `la saisie est nettoyee`() {
        assertEquals("Le Mans", SchoolSearch.normalizeQuery("  Le   Mans "))
    }
}
