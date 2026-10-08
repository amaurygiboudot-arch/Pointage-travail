package com.amaury.pointage

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2

/**
 * Indicateur persistant du pointage dans la barre système Android.
 *
 * Source de vérité : état canonique déjà résolu par IconSwitcher + pending GPS V2.
 * Aucune donnée métier n'est dupliquée ici.
 */
object PointageStatusNotificationV2 {
    const val NOTIFICATION_ID = 24_082
    private const val CHANNEL_ID = "pointage_status_v2"
    private const val PREFS = "pointage_status_notification_v2"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PERMISSION_REQUESTED = "permission_requested"

    internal enum class DisplayState {
        RED,
        GREEN,
        ORANGE
    }

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) {
            IconSwitcher.sync(context)
        } else {
            cancel(context)
        }
    }

    fun permissionWasRequested(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PERMISSION_REQUESTED, false)

    fun markPermissionRequested(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PERMISSION_REQUESTED, true)
            .apply()
    }

    internal fun resolveDisplayState(
        iconState: IconSwitcher.IconState?,
        hasPendingGpsEvent: Boolean
    ): DisplayState = when {
        hasPendingGpsEvent -> DisplayState.ORANGE
        iconState == null -> DisplayState.ORANGE
        iconState == IconSwitcher.IconState.PAUSED -> DisplayState.ORANGE
        iconState == IconSwitcher.IconState.WORKING -> DisplayState.GREEN
        else -> DisplayState.RED
    }

    internal fun shortCriticalText(displayState: DisplayState): String = when (displayState) {
        DisplayState.RED -> "🔴"
        DisplayState.GREEN -> "🟢"
        DisplayState.ORANGE -> "🟠"
    }

    internal fun sync(context: Context, iconState: IconSwitcher.IconState?) {
        val app = context.applicationContext
        if (!isEnabled(app)) {
            cancel(app)
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val pending = GpsWorkStateCoordinatorV2.pending(app)
        val displayState = resolveDisplayState(iconState, pending != null)
        val spec = when (displayState) {
            DisplayState.RED -> StatusSpec(
                color = Color.parseColor("#E53935"),
                icon = R.drawable.ic_pointage_status_red,
                title = "HoraTrack — non pointé",
                text = "🔴 Aucune session de travail en cours."
            )
            DisplayState.GREEN -> StatusSpec(
                color = Color.parseColor("#00C853"),
                icon = R.drawable.ic_pointage_status_green,
                title = "HoraTrack — pointage en cours",
                text = "🟢 Session de travail active."
            )
            DisplayState.ORANGE -> StatusSpec(
                color = Color.parseColor("#FB8C00"),
                icon = R.drawable.ic_pointage_status_orange,
                title = when {
                    pending != null -> "HoraTrack — action requise"
                    iconState == IconSwitcher.IconState.PAUSED -> "HoraTrack — pause en cours"
                    else -> "HoraTrack — état à vérifier"
                },
                text = when {
                    pending != null -> "🟠 Un événement GPS attend ta confirmation."
                    iconState == IconSwitcher.IconState.PAUSED -> "🟠 Une pause est actuellement ouverte."
                    else -> "🟠 Le pointage ne peut pas être certifié pour le moment."
                }
            )
        }

        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(manager)

        val openIntent = Intent(app, MainActivity::class.java).apply {
            putExtra("open_tab", "today")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(spec.icon)
            .setColor(spec.color)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(shortCriticalText(displayState))
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val manager = context.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "État du pointage",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Indicateur permanent rouge, vert ou orange de l'état HoraTrack"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private data class StatusSpec(
        val color: Int,
        val icon: Int,
        val title: String,
        val text: String
    )
}
