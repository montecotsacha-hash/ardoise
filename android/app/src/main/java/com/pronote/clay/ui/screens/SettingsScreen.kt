package com.pronote.clay.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pronote.clay.data.backup.BackupManager
import com.pronote.clay.data.remote.UpdateStatus
import com.pronote.clay.notifications.LocalNotifications
import com.pronote.clay.notifications.NotificationStatus
import com.pronote.clay.ui.components.ConfirmDeleteDialog
import com.pronote.clay.ui.components.ClayButton
import com.pronote.clay.ui.components.ClayDivider
import com.pronote.clay.ui.components.ClayPill
import com.pronote.clay.ui.components.ClayReveal
import com.pronote.clay.ui.components.ClaySecondaryButton
import com.pronote.clay.ui.components.ClaySettingRow
import com.pronote.clay.ui.components.ClaySurface
import com.pronote.clay.ui.components.UpdateAvailableDialog
import com.pronote.clay.ui.theme.ClayDimens
import com.pronote.clay.ui.theme.frosted
import com.pronote.clay.ui.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SettingsScreen — the "Paramètres" section: preferences, account actions and
 * the privacy reminder. It is a real screen (own top bar, own back action)
 * instead of a sheet, so it can be reached from the navigation menu and can
 * never overlap another screen.
 *
 * Each card owns its slice of the preferences: they all observe the same
 * [HomeViewModel.preferences] flow, so a switch still updates the screen as a
 * whole while every card stays readable on its own.
 */
@Composable
fun SettingsScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    val updateStatus by viewModel.updateStatus.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        SettingsTopBar(onBack = onBack)

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ClayDimens.ScreenPadding)
                .padding(bottom = 120.dp),
        ) {
            ClayReveal { SyncCard(viewModel) }

            Spacer(Modifier.height(18.dp))

            ClayReveal(index = 1) { NotificationsCard() }

            Spacer(Modifier.height(18.dp))

            ClayReveal(index = 2) { AppearanceCard(viewModel) }

            Spacer(Modifier.height(18.dp))

            ClayReveal(index = 3) { SecurityCard(viewModel) }

            Spacer(Modifier.height(18.dp))

            ClayReveal(index = 4) { BackupCard(viewModel) }

            Spacer(Modifier.height(18.dp))

            ClayReveal(index = 5) { UpdateCard(viewModel, updateStatus) }

            Spacer(Modifier.height(18.dp))

            ClayReveal(index = 6) { AccountCard(viewModel, onDelete = { confirmDelete = true }) }

            Spacer(Modifier.height(18.dp))

            ClayReveal(index = 7) { PrivacyNote(viewModel) }
        }
    }

    ConfirmDeleteDialog(
        visible = confirmDelete,
        onDismiss = { confirmDelete = false },
        onConfirm = {
            confirmDelete = false
            viewModel.deleteLocalData {}
        },
    )
}

/** Synchronisation automatique et alertes locales. */
@Composable
private fun SyncCard(viewModel: HomeViewModel) {
    val preferences by viewModel.preferences.collectAsState()

    ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 10.dp) {
        Column(Modifier.padding(18.dp)) {
            ClaySettingRow(
                title = "Synchronisation automatique",
                description = "Synchronise périodiquement tes notes quand l'appareil est connecté à Internet.",
            ) {
                Switch(
                    checked = preferences.autoSync,
                    onCheckedChange = {
                        viewModel.updatePreferences(preferences.copy(autoSync = it))
                    },
                )
            }
            ClayDivider()
            ClaySettingRow(
                title = "Nouvelles notes",
                description = "Une alerte locale après une synchronisation en arrière-plan.",
            ) {
                Switch(
                    checked = preferences.notifyNewGrades,
                    onCheckedChange = {
                        viewModel.updatePreferences(preferences.copy(notifyNewGrades = it))
                    },
                )
            }
            ClayDivider()
            ClaySettingRow(
                title = "Changement de moyenne",
                description = "Préviens-moi quand la moyenne générale bouge d'au moins 0,05.",
            ) {
                Switch(
                    checked = preferences.notifyAverageChange,
                    onCheckedChange = {
                        viewModel.updatePreferences(preferences.copy(notifyAverageChange = it))
                    },
                )
            }
            ClayDivider()
            val context = LocalContext.current
            var backgroundAllowed by remember { mutableStateOf(isBackgroundSyncAllowed(context)) }
            // L'exemption s'accorde dans un écran système : au retour, on relit
            // l'état réel. Sans ça la pastille restait sur « Autoriser » alors
            // qu'Android venait d'accorder l'exemption.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, context) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        backgroundAllowed = isBackgroundSyncAllowed(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            ClaySettingRow(
                title = "Alertes même application fermée",
                description = if (backgroundAllowed) {
                    "Android peut réveiller Ardoise en arrière-plan : les nouvelles notes arrivent sans ouvrir l'app."
                } else {
                    "Android gèle les réveils en arrière-plan : les alertes n'arrivent qu'à l'ouverture. Autorise Ardoise à ignorer l'optimisation de batterie."
                },
            ) {
                ClayPill(
                    text = if (backgroundAllowed) "Autorisé" else "Autoriser",
                    tint = if (backgroundAllowed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                    onClick = if (backgroundAllowed) {
                        null
                    } else {
                        { requestBackgroundSync(context) }
                    },
                )
            }
        }
    }
}

/**
 * Android regroupe les réveils en arrière-plan (Doze) et certains
 * constructeurs les gèlent complètement : sans exemption de batterie, une
 * synchronisation périodique ne tourne qu'à l'ouverture de l'app, donc les
 * notifications n'arrivent qu'à ce moment-là.
 */
private fun isBackgroundSyncAllowed(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

private fun requestBackgroundSync(context: Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    runCatching { context.startActivity(intent) }
}

/**
 * Alertes : l'état RÉEL côté Android, le dernier essai et une preuve.
 *
 * Le réglage « Nouvelles notes » plus haut ne suffit pas : sans permission,
 * sans notifications système ou avec un canal muet, rien n'arriverait. Cette
 * carte nomme le blocage et offre le seul bouton qui le lève.
 */
@Composable
private fun NotificationsCard() {
    val context = LocalContext.current
    var status by remember { mutableStateOf(LocalNotifications.status(context)) }

    // La permission se donne dans une feuille système : on relit l'état au
    // retour, jamais en supposant le résultat.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = LocalNotifications.status(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        status = LocalNotifications.status(context)
        if (granted) {
            LocalNotifications.notifyTest(context)
            status = LocalNotifications.status(context)
        }
    }

    ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 10.dp) {
        Column(Modifier.padding(18.dp)) {
            Section("Alertes")
            ClaySettingRow(
                title = status.title(),
                description = status.hint(),
            ) {
                when {
                    !status.permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                        ClayPill(
                            text = "Autoriser",
                            onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        )

                    !status.available -> ClayPill(
                        text = "Ouvrir",
                        onClick = { openNotificationSettings(context) },
                    )

                    else -> ClayPill(
                        text = "Tester",
                        onClick = {
                            LocalNotifications.notifyTest(context)
                            status = LocalNotifications.status(context)
                        },
                    )
                }
            }
            val lastAttempt = status.lastAttemptAt
            if (lastAttempt != null) {
                ClayDivider()
                Text(
                    buildString {
                        append(
                            if (status.lastAttemptDelivered) {
                                "Dernière alerte envoyée le "
                            } else {
                                "Dernier essai bloqué par Android le "
                            },
                        )
                        append(formatAttempt(lastAttempt))
                        append('.')
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

private fun NotificationStatus.title(): String = when {
    !permissionGranted -> "Notifications refusées"
    !enabled -> "Notifications coupées"
    channelBlocked -> "Canal en sourdine"
    else -> "Notifications autorisées"
}

private fun NotificationStatus.hint(): String = when {
    !permissionGranted ->
        "Android n'a pas donné la permission : aucune alerte ne peut s'afficher."

    !enabled ->
        "Les notifications d'Ardoise sont désactivées dans les réglages Android."

    channelBlocked ->
        "Le canal « Suivi scolaire » est muet : réactive-le dans les réglages Android."

    else ->
        "Une alerte locale part après chaque synchronisation qui trouve une nouvelle note."
}

private fun openNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(intent) }
}

private fun formatAttempt(at: Long): String =
    SimpleDateFormat("d MMM 'à' HH:mm", Locale.FRENCH).format(Date(at))

/** Thème, couleurs dynamiques et prénom affiché. */
@Composable
private fun AppearanceCard(viewModel: HomeViewModel) {
    val preferences by viewModel.preferences.collectAsState()

    ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 10.dp) {
        Column(Modifier.padding(18.dp)) {
            Section("Apparence")
            // Les trois puces passent sous le libellé : à côté, elles
            // écrasaient le texte sur un écran de 360 dp (constaté sur
            // émulateur, le titre finissait coupé en plein mot).
            Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Text("Thème", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sombre, clair ou système.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    ThemeChip("Système", preferences.darkTheme == null) {
                        viewModel.updatePreferences(preferences.copy(darkTheme = null))
                    }
                    ThemeChip("Clair", preferences.darkTheme == false) {
                        viewModel.updatePreferences(preferences.copy(darkTheme = false))
                    }
                    ThemeChip("Sombre", preferences.darkTheme == true) {
                        viewModel.updatePreferences(preferences.copy(darkTheme = true))
                    }
                }
            }
            ClayDivider()
            ClaySettingRow(
                title = "Couleurs Material You",
                description = "Reprends les couleurs de ton fond d'écran (Android 12+).",
            ) {
                Switch(
                    checked = preferences.dynamicColor,
                    onCheckedChange = {
                        viewModel.updatePreferences(preferences.copy(dynamicColor = it))
                    },
                )
            }
            ClayDivider()
            ClaySettingRow(
                title = "Réduire les animations",
                description = "Plus d'apparitions ni de glissements : les écrans changent " +
                    "d'un coup. Suit aussi le réglage d'accessibilité d'Android.",
            ) {
                Switch(
                    checked = preferences.reduceMotion,
                    onCheckedChange = {
                        viewModel.updatePreferences(preferences.copy(reduceMotion = it))
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = preferences.displayName.orEmpty(),
                onValueChange = {
                    viewModel.updatePreferences(
                        preferences.copy(displayName = it.trim().ifBlank { null }),
                    )
                },
                label = { Text("Prénom à afficher") },
                supportingText = { Text("Remplace le prénom Pronote après « Bonjour ».") },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Verrouillage biométrique de l'application. */
@Composable
private fun SecurityCard(viewModel: HomeViewModel) {
    val preferences by viewModel.preferences.collectAsState()

    ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 10.dp) {
        Column(Modifier.padding(18.dp)) {
            Section("Sécurité")
            ClaySettingRow(
                title = "Verrouillage biométrique",
                description = "Empreinte, visage ou code de l'appareil à chaque ouverture de l'app. Nécessite un verrou d'appareil configuré.",
            ) {
                Switch(
                    checked = preferences.biometricLock,
                    onCheckedChange = {
                        viewModel.updatePreferences(preferences.copy(biometricLock = it))
                    },
                )
            }
        }
    }
}

/** Quelle action attend la phrase secrète. */
private sealed interface BackupDialog {
    data object Export : BackupDialog
    data class Restore(val uri: Uri) : BackupDialog
}

/**
 * Sauvegarde chiffrée : un fichier local, une phrase secrète, aucun serveur.
 *
 * L'export passe par le sélecteur de fichiers d'Android (SAF) : Ardoise ne
 * demande aucune permission de stockage et l'utilisateur choisit lui-même où
 * le fichier atterrit.
 */
@Composable
private fun BackupCard(viewModel: HomeViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }
    var passphrase by remember { mutableStateOf("") }
    // La phrase d'export attend le retour du sélecteur de fichiers : le
    // dialogue est déjà fermé quand le fichier est réellement écrit.
    var exportPhrase by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    fun report(error: Throwable?) {
        busy = false
        failed = error != null
        feedback = error?.message ?: "Sauvegarde terminée."
    }

    val createFile = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val phrase = exportPhrase
        exportPhrase = ""
        val accountId = viewModel.container.activeAccountId()
        if (uri == null || accountId == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = viewModel.container.backup.export(accountId, phrase.toCharArray())
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("Fichier inaccessible.")
                }
            }
            busy = false
            failed = outcome.isFailure
            feedback = if (outcome.isSuccess) {
                "Sauvegarde chiffrée enregistrée."
            } else {
                "Impossible d'écrire le fichier."
            }
        }
    }

    val openFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) dialog = BackupDialog.Restore(uri)
    }

    ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 10.dp) {
        Column(Modifier.padding(18.dp)) {
            Section("Sauvegarde")
            Text(
                "Tes notes, ton emploi du temps et ton travail, dans un seul fichier " +
                    "chiffré. Tes identifiants et ta session n'y sont jamais.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    ClayButton(
                        text = "Exporter",
                        onClick = {
                            feedback = null
                            failed = false
                            passphrase = ""
                            dialog = BackupDialog.Export
                        },
                        enabled = !busy,
                        loading = busy,
                    )
                }
                Box(Modifier.weight(1f)) {
                    ClaySecondaryButton(
                        text = "Restaurer",
                        onClick = {
                            feedback = null
                            failed = false
                            openFile.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        loading = busy,
                    )
                }
            }
            feedback?.let { message ->
                Spacer(Modifier.height(10.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        }
    }

    val current = dialog
    if (current != null) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    dialog = null
                    passphrase = ""
                }
            },
            title = {
                Text(
                    if (current is BackupDialog.Export) {
                        "Chiffrer la sauvegarde"
                    } else {
                        "Déverrouiller la sauvegarde"
                    },
                )
            },
            text = {
                Column {
                    Text(
                        "Choisis une phrase secrète d'au moins " +
                            "${BackupManager.MIN_PASSPHRASE} caractères. Sans elle, le fichier " +
                            "est illisible — même pour Ardoise. Elle n'est stockée nulle part.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Phrase secrète") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = passphrase.length >= BackupManager.MIN_PASSPHRASE && !busy,
                    onClick = {
                        val phrase = passphrase
                        passphrase = ""
                        dialog = null
                        when (current) {
                            is BackupDialog.Export -> {
                                exportPhrase = phrase
                                createFile.launch(defaultBackupName())
                            }

                            is BackupDialog.Restore -> {
                                val accountId = viewModel.container.activeAccountId()
                                if (accountId == null) {
                                    failed = true
                                    feedback = "Connecte-toi avant de restaurer une sauvegarde."
                                    return@TextButton
                                }
                                busy = true
                                scope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val bytes = context.contentResolver
                                                .openInputStream(current.uri)
                                                ?.use { it.readBytes() }
                                                ?: error("Fichier inaccessible.")
                                            viewModel.container.backup.restore(
                                                accountId,
                                                bytes,
                                                phrase.toCharArray(),
                                            )
                                        }
                                    }
                                    if (outcome.isSuccess) {
                                        viewModel.reloadFromCache()
                                        busy = false
                                        failed = false
                                        feedback = "Sauvegarde restaurée."
                                    } else {
                                        report(outcome.exceptionOrNull())
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text(if (current is BackupDialog.Export) "Chiffrer" else "Restaurer")
                }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null; passphrase = "" }) {
                    Text("Annuler")
                }
            },
        )
    }
}

private fun defaultBackupName(): String = "ardoise-${LocalDate.now()}.ard"

/** Version installée, état de la vérification et téléchargement de l'APK. */
@Composable
private fun UpdateCard(viewModel: HomeViewModel, updateStatus: UpdateStatus) {
    // Le téléchargement se fait dans l'app : le dialogue montre l'avancement
    // puis ouvre l'installateur Android.
    var showUpdate by remember { mutableStateOf(false) }

    ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 10.dp) {
        Column(Modifier.padding(18.dp)) {
            Section("Mise à jour")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Version installée",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    viewModel.container.installedVersionLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            when (val status = updateStatus) {
                is UpdateStatus.Available -> {
                    Text(
                        "Ardoise ${status.info.versionName ?: "plus récente"} " +
                            "est disponible sur le site de distribution.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    ClayButton(
                        text = "Télécharger la mise à jour",
                        onClick = { showUpdate = true },
                    )
                    if (showUpdate) {
                        UpdateAvailableDialog(
                            info = status.info,
                            onDismiss = { showUpdate = false },
                        )
                    }
                }
                UpdateStatus.UpToDate -> Text(
                    "Tu as la dernière version publiée.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateStatus.Checking -> Text(
                    "Vérification…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateStatus.Unavailable -> Text(
                    "Impossible de vérifier pour l'instant (hors ligne ?).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                UpdateStatus.Idle -> Unit
            }
            Spacer(Modifier.height(10.dp))
            ClaySecondaryButton(
                text = "Vérifier les mises à jour",
                onClick = { viewModel.checkForUpdate() },
            )
        }
    }
}

/** Déconnexion et suppression des données locales. */
@Composable
private fun AccountCard(viewModel: HomeViewModel, onDelete: () -> Unit) {
    ClaySurface(modifier = Modifier.fillMaxWidth(), depth = 10.dp) {
        Column(Modifier.padding(18.dp)) {
            Section("Compte")
            ClaySecondaryButton(
                text = "Se déconnecter",
                icon = Icons.AutoMirrored.Rounded.Logout,
                onClick = { viewModel.logout {} },
            )
            Spacer(Modifier.height(10.dp))
            ClayButton(
                text = "Supprimer mes données",
                icon = Icons.Rounded.Delete,
                tint = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                onClick = onDelete,
            )
        }
    }
}

/** Rappel de confidentialité, sous les cartes de réglages. */
@Composable
private fun PrivacyNote(viewModel: HomeViewModel) {
    Column {
        Text(
            "Connecteur : ${viewModel.connectorLabel}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Tes identifiants sont chiffrés par le Keystore Android et ne " +
                "quittent jamais cet appareil. Aucune donnée scolaire n'est " +
                "envoyée à un serveur tiers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    // En-tête minimal, sans cadre : un retour et le titre, rien de plus.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour")
        }
        Box(Modifier.weight(1f)) {
            Text(
                "Réglages",
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-0.8).sp,
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.padding(end = 6.dp),
    )
}
