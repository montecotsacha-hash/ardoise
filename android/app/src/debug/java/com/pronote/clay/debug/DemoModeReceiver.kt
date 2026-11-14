package com.pronote.clay.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.pronote.clay.AppContainer
import com.pronote.clay.data.remote.FakePronoteDataSource
import java.io.File

/**
 * Active les données de démonstration dans un build de debug.
 *
 * `adb shell am broadcast -a com.pronote.clay.DEMO -n
 * com.pronote.clay/.debug.DemoModeReceiver`, puis relancer l'application : elle
 * se connecte avec n'importe quels identifiants et le connecteur factice
 * répond. Sert aux captures et aux essais sur émulateur, sans compte Pronote.
 *
 * Le témoin est un fichier, pas seulement un champ statique : le conteneur
 * d'injection est construit au démarrage du processus, donc avant l'arrivée du
 * broadcast. `AppContainer` ne le lit que dans un build debuggable, ce qui rend
 * ce receiver sans effet sur l'APK publié — où il n'est même pas compilé.
 */
class DemoModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppContainer.debugConnector = FakePronoteDataSource()
        runCatching {
            File(context.filesDir, AppContainer.DEMO_MODE_FILE).writeText("on")
        }
    }
}
