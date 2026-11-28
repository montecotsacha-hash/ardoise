package com.pronote.clay.data.backup

import com.pronote.clay.data.local.LocalDataSource
import org.json.JSONObject

/**
 * BackupManager — exporte et restaure les données d'un compte.
 *
 * Ce qui part : notes, matières, périodes, historique, emploi du temps
 * mémorisé, cahier de textes et vie scolaire. Ce qui ne part JAMAIS :
 * identifiants, jeton de session et adresse de l'établissement. Une sauvegarde
 * volée ne permet donc pas de se connecter au compte Pronote.
 */
class BackupManager(private val local: LocalDataSource) {

    /** Octets chiffrés, prêts pour le fichier choisi par l'utilisateur. */
    fun export(accountId: String, passphrase: CharArray): ByteArray {
        val payload = JSONObject()
            .put("format", FORMAT)
            .put("exportedAt", System.currentTimeMillis())
            .put("data", local.exportAccount(accountId))
        return EncryptedBackup.encrypt(payload.toString().toByteArray(Charsets.UTF_8), passphrase)
    }

    /** Restaure dans [accountId] ; lève [EncryptedBackup.WrongPassword] si besoin. */
    fun restore(accountId: String, bytes: ByteArray, passphrase: CharArray) {
        if (!EncryptedBackup.looksLikeBackup(bytes)) throw EncryptedBackup.NotABackup()
        val plain = EncryptedBackup.decrypt(bytes, passphrase)
        val payload = runCatching { JSONObject(String(plain, Charsets.UTF_8)) }
            .getOrElse { throw EncryptedBackup.NotABackup() }
        if (payload.optInt("format") != FORMAT) throw EncryptedBackup.NotABackup()
        val data = payload.optJSONObject("data") ?: throw EncryptedBackup.NotABackup()
        local.importAccount(accountId, data)
    }

    companion object {
        /** Version du format : une sauvegarde d'une autre version est refusée. */
        const val FORMAT = 1

        /** Longueur minimale de la phrase secrète, côté interface. */
        const val MIN_PASSPHRASE = 8
    }
}
