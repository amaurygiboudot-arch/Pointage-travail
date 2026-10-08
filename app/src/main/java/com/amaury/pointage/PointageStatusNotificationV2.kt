package com.amaury.pointage

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private const val KEY_OPACITY_PERCENT = "icon_opacity_percent"

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

    fun opacityPercent(context: Context): Int =
        opacityBucket(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_OPACITY_PERCENT, 100)
        )

    fun setOpacityPercent(context: Context, value: Int) {
        val app = context.applicationContext
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_OPACITY_PERCENT, opacityBucket(value))
            .apply()
        IconSwitcher.sync(app)
    }

    internal fun opacityBucket(value: Int): Int {
        val clamped = value.coerceIn(25, 100)
        return (((clamped + 12) / 25) * 25).coerceIn(25, 100)
    }

    internal fun resolveAccentColor(displayState: DisplayState, dark: Boolean): Int = when (displayState) {
        DisplayState.RED -> if (dark) 0xFFEF5350.toInt() else 0xFFC62828.toInt()
        DisplayState.GREEN -> if (dark) 0xFF66BB6A.toInt() else 0xFF2E7D32.toInt()
        DisplayState.ORANGE -> if (dark) 0xFFFFB74D.toInt() else 0xFFEF6C00.toInt()
    }

    internal fun iconForState(displayState: DisplayState, opacity: Int): Int = when (displayState) {
        DisplayState.RED -> when (opacityBucket(opacity)) {
            25 -> R.drawable.ic_pointage_status_red_25
            50 -> R.drawable.ic_pointage_status_red_50
            75 -> R.drawable.ic_pointage_status_red_75
            else -> R.drawable.ic_pointage_status_red
        }
        DisplayState.GREEN -> when (opacityBucket(opacity)) {
            25 -> R.drawable.ic_pointage_status_green_25
            50 -> R.drawable.ic_pointage_status_green_50
            75 -> R.drawable.ic_pointage_status_green_75
            else -> R.drawable.ic_pointage_status_green
        }
        DisplayState.ORANGE -> when (opacityBucket(opacity)) {
            25 -> R.drawable.ic_pointage_status_orange_25
            50 -> R.drawable.ic_pointage_status_orange_50
            75 -> R.drawable.ic_pointage_status_orange_75
            else -> R.drawable.ic_pointage_status_orange
        }
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

    internal fun chronometerStartMs(
        displayState: DisplayState,
        sessionStartedAtMs: Long?,
        nowMs: Long
    ): Long? {
        if (displayState == DisplayState.RED) return null
        val start = sessionStartedAtMs ?: return null
        return start.takeIf { it > 0L && it <= nowMs }
    }

    internal fun sync(
        context: Context,
        iconState: IconSwitcher.IconState?,
        sessionStartedAtMs: Long? = null
    ) {
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
        val dark = AppThemeCatalog.useDarkPalette(app)
        val opacity = opacityPercent(app)
        val accent = resolveAccentColor(displayState, dark)
        val statusIcon = iconForState(displayState, opacity)
        val spec = when (displayState) {
            DisplayState.RED -> StatusSpec(
                color = accent,
                icon = statusIcon,
                title = "Hors",
                text = "🔴 Aucune session de travail en cours."
            )
            DisplayState.GREEN -> StatusSpec(
                color = accent,
                icon = statusIcon,
                title = "Travail",
                text = "🟢 Session de travail active."
            )
            DisplayState.ORANGE -> StatusSpec(
                color = accent,
                icon = statusIcon,
                title = when {
                    pending != null -> "Vérifier"
                    iconState == IconSwitcher.IconState.PAUSED -> "Pause"
                    else -> "Vérifier"
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

        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(spec.icon)
            .setColor(spec.color)
            .setRequestPromotedOngoing(true)
            // HyperOS affiche shortCriticalText dans le chip. Un caractère zéro-largeur
            // conserve la pastille promue tout en ne laissant visible que notre icône d'état.
            .setShortCriticalText("\u200B")
            .setContentTitle(spec.title)
            .setContentText(spec.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.text))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)

        val chronometerStart = chronometerStartMs(
            displayState = displayState,
            sessionStartedAtMs = sessionStartedAtMs,
            nowMs = System.currentTimeMillis()
        )
        if (chronometerStart != null) {
            // Android anime lui-même ce chrono : aucun réveil périodique de HoraTrack.
            // Il mesure la présence depuis l'entrée réelle, pas le temps payé.
            builder
                .setWhen(chronometerStart)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
        } else {
            builder.setShowWhen(false)
        }

        manager.notify(NOTIFICATION_ID, builder.build())
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
