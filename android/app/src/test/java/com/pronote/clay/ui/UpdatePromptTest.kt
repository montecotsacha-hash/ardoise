package com.pronote.clay.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pronote.clay.data.remote.UpdateInfo
import com.pronote.clay.ui.components.UpdateAvailableDialog
import com.pronote.clay.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * La proposition de mise à jour, au doigt : elle annonce la version publiée,
 * démarre le téléchargement dans l'app et peut être reportée.
 *
 * Même mode graphique natif que les autres tests d'interface du projet : sans
 * lui, les clics injectés ne se comportent pas comme sur un appareil.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdatePromptTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `la proposition annonce la version et télécharge dans l'app`() {
        composeRule.setContent {
            AppTheme {
                UpdateAvailableDialog(
                    info = UpdateInfo(
                        versionName = "1.5.0",
                        versionCode = 12,
                        size = 11_952_691,
                        sha256 = "ab12",
                        apkUrl = "https://site.example/Ardoise.apk",
                    ),
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Mise à jour d'Ardoise").assertExists()
        composeRule.onNodeWithText("Version 1.5.0", substring = true).assertExists()

        // Le bouton ne renvoie plus vers le site : il lance le téléchargement
        // intégré, dont l'état s'affiche dans le même dialogue.
        composeRule.onNodeWithText("Télécharger").performClick()
        // Le bouton de départ a laissé place à l'état de téléchargement : la
        // demande ne renvoie plus vers le site, elle télécharge ici.
        composeRule.onNodeWithText("Télécharger").assertDoesNotExist()
        composeRule.onAllNodesWithText("Téléchargement", substring = true)
            .onFirst()
            .assertExists()
    }
}
