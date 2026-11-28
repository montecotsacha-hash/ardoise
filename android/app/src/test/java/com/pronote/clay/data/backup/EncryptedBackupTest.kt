package com.pronote.clay.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EncryptedBackupTest {

    private val passphrase = "ardoise-2026".toCharArray()

    @Test
    fun `un aller-retour rend exactement les octets d'origine`() {
        val plain = """{"format":1,"data":{"moyenne":14.5}}""".toByteArray()

        val sealed = EncryptedBackup.encrypt(plain, passphrase)

        assertArrayEquals(plain, EncryptedBackup.decrypt(sealed, passphrase))
    }

    @Test
    fun `le contenu chiffre ne laisse pas lire les donnees`() {
        val plain = """{"moyenne":14.5}""".toByteArray()

        val sealed = EncryptedBackup.encrypt(plain, passphrase)
        val asText = String(sealed, Charsets.ISO_8859_1)

        assertFalse(asText.contains("moyenne"))
        assertFalse(asText.contains("14.5"))
        assertTrue(EncryptedBackup.looksLikeBackup(sealed))
    }

    @Test
    fun `une mauvaise phrase secrete est refusee`() {
        val sealed = EncryptedBackup.encrypt("secret".toByteArray(), passphrase)

        try {
            EncryptedBackup.decrypt(sealed, "mauvais-mot-de-passe".toCharArray())
            fail("Le déchiffrement aurait dû échouer.")
        } catch (_: EncryptedBackup.WrongPassword) {
            // Attendu : GCM refuse le tag d'authentification.
        }
    }

    @Test
    fun `un octet modifie invalide la sauvegarde`() {
        val sealed = EncryptedBackup.encrypt("secret".toByteArray(), passphrase)
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 0x01).toByte()

        try {
            EncryptedBackup.decrypt(sealed, passphrase)
            fail("Un fichier altéré ne doit jamais être restauré.")
        } catch (_: EncryptedBackup.WrongPassword) {
            // Attendu.
        }
    }

    @Test
    fun `un fichier quelconque est reconnu comme tel`() {
        val random = ByteArray(64) { it.toByte() }

        assertFalse(EncryptedBackup.looksLikeBackup(random))
        try {
            EncryptedBackup.decrypt(random, passphrase)
            fail("Un fichier non-Ardoise doit être refusé.")
        } catch (_: EncryptedBackup.NotABackup) {
            // Attendu.
        }
    }

    @Test
    fun `deux chiffrements du meme contenu different`() {
        val plain = "identique".toByteArray()

        val first = EncryptedBackup.encrypt(plain, passphrase)
        val second = EncryptedBackup.encrypt(plain, passphrase)

        assertFalse(first.contentEquals(second))
        assertArrayEquals(plain, EncryptedBackup.decrypt(first, passphrase))
        assertArrayEquals(plain, EncryptedBackup.decrypt(second, passphrase))
    }
}
