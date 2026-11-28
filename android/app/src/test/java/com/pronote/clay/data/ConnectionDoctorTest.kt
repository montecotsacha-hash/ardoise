package com.pronote.clay.data

import com.pronote.clay.data.remote.ConnectionDoctor
import com.pronote.clay.data.remote.DiagnosisKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le diagnostic doit nommer la cause : une panne, une adresse fausse et une IP
 * bloquée n'appellent pas la même réaction.
 */
class ConnectionDoctorTest {

    private val page = """
        <html><head><title>PRONOTE</title></head>
        <body><span>PRONOTE 2026.1.3.1</span>
        <script>Start ( {"a":1} );}</script></body></html>
    """.trimIndent()

    @Test
    fun `une page pronote est reconnue avec sa version`() {
        val diagnosis = ConnectionDoctor.classify(200, page)
        assertEquals(DiagnosisKind.READY, diagnosis.kind)
        assertEquals("2026.1.3.1", diagnosis.pronoteVersion)
    }

    @Test
    fun `une ip suspendue est distinguee d'une panne`() {
        val diagnosis = ConnectionDoctor.classify(
            200,
            "Votre adresse IP est provisoirement suspendue.",
        )
        assertEquals(DiagnosisKind.SUSPENDED_IP, diagnosis.kind)
        assertTrue(diagnosis.message.contains("bloque"))
    }

    @Test
    fun `une maintenance est reconnue`() {
        assertEquals(
            DiagnosisKind.BUSY,
            ConnectionDoctor.classify(200, "Le site est momentanément indisponible.").kind,
        )
    }

    @Test
    fun `une page inconnue qui répond est traitee comme un ENT`() {
        assertEquals(
            DiagnosisKind.ENT_OR_WRONG_PAGE,
            ConnectionDoctor.classify(200, "<html>Choisissez votre ENT</html>").kind,
        )
    }

    @Test
    fun `une 404 est une adresse fausse`() {
        assertEquals(DiagnosisKind.NOT_FOUND, ConnectionDoctor.classify(404, "").kind)
    }

    @Test
    fun `un code 500 reste une adresse en erreur`() {
        assertEquals(DiagnosisKind.NOT_FOUND, ConnectionDoctor.classify(500, "").kind)
    }
}
