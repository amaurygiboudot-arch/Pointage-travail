package com.amaury.pointage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Rend visible une sortie GPS qui attend la confirmation de l'utilisateur.
 *
 * Le GPS ne ferme jamais la session ici : la notification rouvre HoraTrack,
 * où la confirmation canonique V2 reste obligatoire.
 */
object GpsExitConfirmationNotificationV2 {
    const val NOTIFICATION_ID = 24_081
    private const val CHANNEL_ID = "gps_exit_confirmation_v2"

    fun show(context: Context) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("open_tab", "today")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Confirmation de sortie GPS",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Demande de confirmation lorsqu'une sortie du lieu de travail est détectée"
                }
            )
        }

        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Sortie du lieu de travail détectée")
                .setContentText("Ouvre HoraTrack pour confirmer la fin de ta journée.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "HoraTrack a détecté que tu as quitté une zone Travail. " +
                            "Ouvre l'application pour confirmer si ta journée est réellement terminée."
                    )
                )
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        )
    }

    fun cancel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }
}
