package com.amaury.pointage

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
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
            notifyFailure(context, "Téléchargement incomplet")
            return
        }

        val apk = UpdateChecker.downloadedApkFile(context)
        if (apk == null || !apk.exists()) {
            UpdateChecker.clearDownloadState(context)
            notifyFailure(context, "Fichier de mise à jour introuvable")
            return
        }

        // Aucune opération réseau ou cryptographique ici : le receiver se termine vite.
        UpdateVerificationWorker.enqueue(context)
    }

    private fun notifyFailure(context: Context, reason: String) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.hp_icon_red)
                .setContentTitle("Mise à jour interrompue")
                .setContentText(reason.take(120))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mises à jour HoraTrack", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "hp_update_download"
        private const val NOTIFICATION_ID = 4107
    }
}
