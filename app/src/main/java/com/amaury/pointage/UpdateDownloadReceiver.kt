package com.amaury.pointage

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class UpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        val prefs = context.getSharedPreferences(UpdateChecker.PREFS, Context.MODE_PRIVATE)
        val expectedId = prefs.getLong(UpdateChecker.KEY_DOWNLOAD_ID, -1L)
        if (completedId <= 0L || completedId != expectedId) return

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val status = runCatching {
            manager.query(DownloadManager.Query().setFilterById(completedId)).use { cursor ->
                if (!cursor.moveToFirst()) return@use -1
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        }.getOrDefault(-1)

        if (status != DownloadManager.STATUS_SUCCESSFUL) {
            UpdateChecker.clearDownloadState(context)
            notifyFailure(context)
            return
        }

        val apk = UpdateChecker.downloadedApkFile(context) ?: run {
            UpdateChecker.clearDownloadState(context)
            notifyFailure(context)
            return
        }

        if (runCatching { UpdateChecker.validateApk(context, apk) }.isFailure) {
            apk.delete()
            UpdateChecker.clearDownloadState(context)
            notifyFailure(context)
            return
        }

        // On conserve l'APK comme « prête à installer ». Ainsi, si Android/HyperOS bloque
        // l'ouverture de l'installateur depuis l'arrière-plan, HP Travail le relancera
        // automatiquement dès qu'il repasse au premier plan.
        UpdateChecker.markDownloadReady(context, apk)
        val installIntent = UpdateChecker.installerIntent(context, apk)

        val pending = PendingIntent.getActivity(
            context,
            4107,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launched = runCatching {
            pending.send()
            true
        }.getOrDefault(false)

        // La notification reste seulement comme secours système. Aucun nouveau téléchargement
        // ni passage manuel par le menu de mise à jour n'est nécessaire.
        if (!launched) notifyReady(context, installIntent)
    }

    private fun notifyReady(context: Context, installIntent: Intent) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            4107,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.hp_icon_red)
            .setContentTitle("Mise à jour HP Travail prête")
            .setContentText("L'installation se lancera automatiquement au retour dans HP Travail.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun notifyFailure(context: Context) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.hp_icon_red)
            .setContentTitle("Mise à jour interrompue")
            .setContentText("HP Travail réessaiera automatiquement.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mises à jour HP Travail", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "hp_update_download"
        private const val NOTIFICATION_ID = 4107
    }
}
