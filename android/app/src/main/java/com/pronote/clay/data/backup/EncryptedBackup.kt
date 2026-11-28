package com.pronote.clay.data.backup

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chiffrement d'une sauvegarde Ardoise.
 *
 * Format du fichier : `ARD1` | sel 16 octets | IV 12 octets | texte chiffré.
 * La clé vient d'une phrase secrète (PBKDF2-HMAC-SHA256, 150 000 itérations,
 * 256 bits) et le contenu est scellé en AES-256-GCM : toute altération du
 * fichier ou erreur de phrase secrète fait échouer le déchiffrement, jamais
 * rendre des données à moitié lisibles.
 *
 * Aucune donnée n'est envoyée : la sauvegarde est un fichier local que
 * l'utilisateur place où il veut.
 */
object EncryptedBackup {

    /** Phrase secrète fausse, ou fichier modifié. */
    class WrongPassword : Exception("Phrase secrète incorrecte ou fichier altéré.")

    /** Fichier qui n'a pas l'en-tête d'une sauvegarde Ardoise. */
    class NotABackup : Exception("Ce fichier n'est pas une sauvegarde Ardoise.")

    private val MAGIC = byteArrayOf(
        'A'.code.toByte(),
        'R'.code.toByte(),
        'D'.code.toByte(),
        '1'.code.toByte(),
    )
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 150_000
    private const val KEY_BITS = 256
    private const val TAG_BYTES = TAG_BITS / 8

    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Une phrase secrète est nécessaire." }
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plain)

        return MAGIC + salt + iv + ciphertext
    }

    fun decrypt(data: ByteArray, passphrase: CharArray): ByteArray {
        val headerSize = MAGIC.size + SALT_SIZE + IV_SIZE
        if (data.size <= headerSize + TAG_BYTES) throw NotABackup()
        if (!data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackup()

        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + SALT_SIZE)
        val iv = data.copyOfRange(MAGIC.size + SALT_SIZE, headerSize)
        val ciphertext = data.copyOfRange(headerSize, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        return try {
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw WrongPassword()
        } catch (_: GeneralSecurityException) {
            throw WrongPassword()
        }
    }

    /** Vrai avant même de demander la phrase : mauvais fichier, message clair. */
    fun looksLikeBackup(data: ByteArray): Boolean =
        data.size > MAGIC.size + SALT_SIZE + IV_SIZE + TAG_BYTES &&
            data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    private fun key(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
                .let { SecretKeySpec(it, "AES") }
        } finally {
            // La phrase ne doit pas traîner dans un tableau du tas.
            spec.clearPassword()
        }
    }
}
