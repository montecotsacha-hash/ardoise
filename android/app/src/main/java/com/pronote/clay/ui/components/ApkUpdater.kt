package com.pronote.clay.ui.components

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pronote.clay.data.remote.UpdateInfo
import com.pronote.clay.ui.theme.LocalFrosted
import com.pronote.clay.ui.theme.frosted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** MIME des APK : Android ouvre l'installateur sur ce type, pas sur un lien web. */
private const val APK_MIME = "application/vnd.android.package-archive"

/** Où en est la mise à jour, du point de vue de l'utilisateur. */
sealed interface ApkUpdateState {
    data object Idle : ApkUpdateState

    /** [progress] vaut null quand la taille totale n'est pas encore connue. */
    data class Downloading(val progress: Float?) : ApkUpdateState

    /** Android exige d'autoriser les installations inconnues pour Ardoise. */
    data object PermissionNeeded : ApkUpdateState

    /** L'installateur Android a été ouvert sur l'APK téléchargé. */
    data object Ready : ApkUpdateState

    data class Failed(val message: String) : ApkUpdateState
}

/**
 * Télécharge l'APK de mise à jour DANS l'application puis ouvre l'installateur
 * Android directement sur ce fichier.
 *
 * Rien n'est laissé au navigateur : `DownloadManager` écrit l'APK dans le
 * stockage privé de l'app, [FileProvider] le partage en lecture seule, et
 * l'installation continue sans quitter Ardoise. Si Android n'autorise pas
 * encore l'installation depuis Ardoise, l'écran système correspondant s'ouvre
 * et le retour dans l'app relance l'installation automatiquement.
 */
@Stable
class ApkDownloader(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf<ApkUpdateState>(ApkUpdateState.Idle)
        private set

    private var pendingFile: File? = null
    private var lastInfo: UpdateInfo? = null
    private var downloadId: Long? = null

    fun start(info: UpdateInfo) {
        if (state is ApkUpdateState.Downloading) return
        lastInfo = info
        state = ApkUpdateState.Downloading(null)
        runCatching {
            val request = DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle("Ardoise ${info.versionName.orEmpty()}".trim())
                .setDescription("Mise à jour de l'application")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "Ardoise-${info.versionCode}.apk",
                )
            val manager = context.getSystemService(DownloadManager::class.java)
                ?: error("gestionnaire de téléchargement indisponible")
            val id = manager.enqueue(request)
            downloadId = id
            scope.launch { follow(manager, id) }
        }.onFailure {
            state = ApkUpdateState.Failed(
                "Téléchargement impossible : " + (it.message ?: "erreur inconnue"),
            )
        }
    }

    private suspend fun follow(manager: DownloadManager, id: Long) {
        repeat(600) {
            delay(400)
            val cursor = runCatching {
                manager.query(DownloadManager.Query().setFilterById(id))
            }.getOrNull()
            if (cursor == null || !cursor.moveToFirst()) {
                state = ApkUpdateState.Failed("Téléchargement introuvable.")
                return
            }
            cursor.use {
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (status) {
                    DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                        val total =
                            it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val done = it.getLong(
                            it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                        )
                        state = ApkUpdateState.Downloading(
                            if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null,
                        )
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val local = it.getString(
                            it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI),
                        )
                        val file = local?.let(::fileOf)
                        if (file == null || !file.exists() || file.length() <= 0L) {
                            state = ApkUpdateState.Failed("L'APK téléchargé est introuvable.")
                            return
                        }
                        pendingFile = file
                        state = ApkUpdateState.Downloading(1f)
                        install(file)
                        return
                    }

                    else -> {
                        val reason = it.getInt(
                            it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON),
                        )
                        state = ApkUpdateState.Failed("Téléchargement interrompu (code $reason).")
                        return
                    }
                }
            }
        }
        state = ApkUpdateState.Failed("Le téléchargement a pris trop de temps.")
    }

    private fun fileOf(localUri: String): File? = runCatching {
        if (localUri.startsWith("file:")) {
            File(Uri.decode(localUri.removePrefix("file://")))
        } else {
            null
        }
    }.getOrNull()

    fun install(file: File? = null) {
        val target = file ?: pendingFile ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            state = ApkUpdateState.PermissionNeeded
            openInstallPermissionSettings()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", target)
        state = ApkUpdateState.Ready
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                    )
                },
            )
        }.onFailure {
            state = ApkUpdateState.Failed(
                "Aucun installateur Android n'a voulu de l'APK : " +
                    (it.message ?: "erreur inconnue"),
            )
        }
    }

    /** Après autorisation dans les réglages système : installe l'APK déjà téléchargé. */
    fun retryInstall() {
        val file = pendingFile
        if (file != null) install(file) else lastInfo?.let(::start)
    }

    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
    }

    /** L'utilisateur reporte : on oublie l'état sans supprimer un APK déjà téléchargé. */
    fun dismiss() {
        if (state !is ApkUpdateState.Downloading) state = ApkUpdateState.Idle
    }
}

/**
 * Un téléchargeur par écran, qui survit aux recompositions. La mise à jour
 * démarre à la demande, jamais au premier affichage.
 */
@Composable
fun rememberApkDownloader(): ApkDownloader {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = remember(context) {
        ApkDownloader(context.applicationContext, scope)
    }
    // Au retour de l'écran système « installer des applications inconnues »,
    // Android a pu accorder l'autorisation : on installe sans redemander.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, downloader) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                downloader.state == ApkUpdateState.PermissionNeeded &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                context.packageManager.canRequestPackageInstalls()
            ) {
                downloader.retryInstall()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return downloader
}

/**
 * Dialogue de mise à jour : il télécharge vraiment l'APK, montre l'avancement,
 * puis ouvre l'installateur Android. Aucun passage par le navigateur.
 */
@Composable
fun UpdateDownloadDialog(
    info: UpdateInfo,
    onDismiss: () -> Unit,
) {
    val downloader = rememberApkDownloader()
    val state = downloader.state

    Dialog(
        onDismissRequest = {
            if (state !is ApkUpdateState.Downloading) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val tokens = LocalFrosted.current
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.34f))
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .frosted(RoundedCornerShape(32.dp), tokens.tint, elevation = 18.dp)
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                Text(
                    "Mise à jour d'Ardoise",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("Version ")
                        append(info.versionName ?: "plus récente")
                        if (info.size != null) {
                            append(" · ")
                            append("%.1f Mo".format(info.size / 1_048_576f))
                        }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(14.dp))

                when (state) {
                    ApkUpdateState.Idle -> {
                        Text(
                            "Ardoise télécharge l'APK ici, dans l'application, puis ouvre " +
                                "l'installateur d'Android. Tes données et ta session sont " +
                                "conservées.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        DialogButtons(
                            dismissLabel = "Plus tard",
                            onDismiss = onDismiss,
                            confirmLabel = "Télécharger",
                            onConfirm = { downloader.start(info) },
                        )
                    }

                    is ApkUpdateState.Downloading -> {
                        val progress = state.progress
                        Text(
                            if (progress == null) "Téléchargement…"
                            else "Téléchargement… ${(progress * 100).toInt()} %",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(14.dp))
                        if (progress == null) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {}, enabled = false) { Text("Téléchargement…") }
                        }
                    }

                    ApkUpdateState.PermissionNeeded -> {
                        Text(
                            "Android demande d'autoriser Ardoise à installer des applications. " +
                                "Accorde-le une fois : l'installation reprendra au retour.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        DialogButtons(
                            dismissLabel = "Plus tard",
                            onDismiss = onDismiss,
                            confirmLabel = "Autoriser",
                            onConfirm = { downloader.openInstallPermissionSettings() },
                        )
                    }

                    ApkUpdateState.Ready -> {
                        Text(
                            "L'APK est téléchargé et l'installateur Android est ouvert. " +
                                "Termine l'installation dans la fenêtre d'Android.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(18.dp))
                        DialogButtons(
                            dismissLabel = "Fermer",
                            onDismiss = onDismiss,
                            confirmLabel = "Rouvrir l'installateur",
                            onConfirm = { downloader.retryInstall() },
                        )
                    }

                    is ApkUpdateState.Failed -> {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(18.dp))
                        DialogButtons(
                            dismissLabel = "Plus tard",
                            onDismiss = onDismiss,
                            confirmLabel = "Réessayer",
                            onConfirm = { downloader.start(info) },
                        )
                    }
                }
            }
        }
    }

    // Le dialogue appartient à la mise à jour en cours : à sa fermeture, l'état
    // repart de zéro pour la prochaine ouverture.
    DisposableEffect(Unit) {
        onDispose { downloader.dismiss() }
    }
}

@Composable
private fun DialogButtons(
    dismissLabel: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onDismiss) {
            Text(dismissLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(6.dp))
        TextButton(onClick = onConfirm) {
            Text(confirmLabel, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Injecté par les tests pour ne jamais toucher au vrai DownloadManager. */
val LocalApkDownloaderOverride = staticCompositionLocalOf<ApkDownloader?> { null }

@Composable
internal fun rememberDialogClickGuard(): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return Modifier.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = {},
    )
}
