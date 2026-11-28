package com.pronote.clay.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pronote.clay.MainActivity
import com.pronote.clay.core.AverageCalculator
import com.pronote.clay.domain.Grade
import com.pronote.clay.domain.Subject

/**
 * Ce qu'Android autorise réellement.
 *
 * Une case cochée dans l'app ne suffit pas : la permission peut être refusée,
 * les notifications coupées au niveau du système, ou le canal mis en sourdine.
 * L'écran de réglages lit cet état au lieu de promettre des alertes muettes.
 */
data class NotificationStatus(
    val permissionGranted: Boolean,
    val enabled: Boolean,
    val channelBlocked: Boolean,
    val lastAttemptAt: Long?,
    val lastAttemptDelivered: Boolean,
) {
    val available: Boolean get() = permissionGranted && enabled && !channelBlocked
}

/**
 * Local notifications — generated on-device after a sync.
 *
 * No school data is ever sent to a third-party service just to produce a
 * notification, and nothing here runs when the user disabled the category.
 */
object LocalNotifications {

    private const val CHANNEL_ID = "pronote_updates"
    private const val NOTIFICATION_ID = 4201
    private const val PREFS = "pronote_notifications"
    private const val KEY_LAST_AT = "lastAttemptAt"
    private const val KEY_LAST_OK = "lastAttemptDelivered"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Suivi scolaire",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Nouvelles notes et évolution de la moyenne générale."
        }
        manager.createNotificationChannel(channel)
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Permission Android 13+, notifications du système et canal, ensemble. */
    fun permissionGranted(context: Context): Boolean = canNotify(context)

    fun enabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Vrai quand l'utilisateur a mis le canal « Suivi scolaire » en sourdine. */
    fun channelBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun status(context: Context): NotificationStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return NotificationStatus(
            permissionGranted = permissionGranted(context),
            enabled = enabled(context),
            channelBlocked = channelBlocked(context),
            lastAttemptAt = prefs.getLong(KEY_LAST_AT, 0L).takeIf { it > 0L },
            lastAttemptDelivered = prefs.getBoolean(KEY_LAST_OK, false),
        )
    }

    /**
     * Notification de test : même canal, même chemin que les vraies alertes.
     * C'est le seul moyen de prouver à l'utilisateur que ça marche vraiment.
     */
    fun notifyTest(context: Context) {
        notify(
            context,
            "Ardoise",
            "Les alertes fonctionnent : tes nouvelles notes arriveront ici.",
        )
    }

    fun notify(context: Context, title: String, body: String) {
        // La vérification vit ICI et pas seulement dans le helper : c'est la
        // seule forme que lint reconnaît, et elle reste vraie à l'exécution.
        recordAttempt(context, delivered = canNotify(context) && enabled(context))
        if (!canNotify(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)

        // Tapping the notification must open the app: MainActivity is the single
        // entry point, brought to the front (or recreated) with its task on top.
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        // Le canal peut être muet alors que la permission est accordée : la
        // notification partirait dans le vide, on l'enregistre comme bloquée.
        if (channelBlocked(context)) {
            recordAttempt(context, delivered = false)
            return
        }

        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.isSuccess
        recordAttempt(context, delivered = posted)
    }

    /** « Dernier essai » : la preuve, datée, que le chemin a été parcouru. */
    private fun recordAttempt(context: Context, delivered: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .putBoolean(KEY_LAST_OK, delivered)
            .apply()
    }

    fun newGradeNotification(grade: Grade, subject: Subject?): String {
        val name = subject?.name?.lowercase() ?: "une matière"
        return "Une nouvelle note de ${AverageCalculator.formatGrade(grade.value, grade.maxValue)} " +
            "en $name vient d'être ajoutée."
    }

    fun averageChangeNotification(next: Double, previous: Double): String {
        val direction = if (next >= previous) "est maintenant de" else "est passée à"
        return "Ta moyenne générale $direction ${AverageCalculator.formatAverage(next)}/20."
    }
}
