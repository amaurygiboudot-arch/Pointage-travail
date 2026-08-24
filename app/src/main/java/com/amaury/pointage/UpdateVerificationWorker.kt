package com.amaury.pointage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class UpdateVerificationWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(UpdateChecker.PREFS, Context.MODE_PRIVATE)
        val version = prefs.getString(UpdateChecker.KEY_VERSION, "").orEmpty()
        val apk = UpdateChecker.downloadedApkFile(context) ?: run {
            prefs.edit().putBoolean(UpdateChecker.KEY_VERIFICATION_PENDING, false).apply()
            return Result.failure()
        }

        return try {
            ApkUpdateVerifier.verify(context, apk, version)
            UpdateChecker.validateApk(context, apk)

            PointageStore.withDurableSnapshotBoundary(context) { currentData ->
                if (currentData.length() > 0 && !DataSafetyGuard.createSnapshot(context)) {
                    throw IllegalStateException("Sauvegarde de sécurité des pointages impossible")
                }
                UpdateChecker.markDownloadReady(context, apk)
            }

            prefs.edit().putBoolean(UpdateChecker.KEY_VERIFICATION_PENDING, false).apply()
            notifyReady(context)
            Result.success()
        } catch (e: ApkUpdateVerifier.RetryableVerificationException) {
            Result.retry()
        } catch (e: Exception) {
            apk.delete()
            prefs.edit().putBoolean(UpdateChecker.KEY_VERIFICATION_PENDING, false).apply()
            UpdateChecker.clearDownloadState(context)
            notifyFailure(context, e.message ?: "Vérification de sécurité échouée")
            Result.failure()
        }
    }

    private fun notifyReady(context: Context) {
        val apk = UpdateChecker.downloadedApkFile(context)
            ?: context.getSharedPreferences(UpdateChecker.PREFS, Context.MODE_PRIVATE)
                .getString(UpdateChecker.KEY_READY_FILE, null)
                ?.let { name -> java.io.File(java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "updates"), name) }
            ?: return
        val installIntent = UpdateChecker.installerIntent(context, apk)
        val pendingIntent = PendingIntent.getActivity(
            context,
            4107,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.hp_icon_red)
                .setContentTitle("Mise à jour HoraTrack prête")
                .setContentText("La mise à jour a été vérifiée et peut être installée.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()
        )
    }

    private fun notifyFailure(context: Context, reason: String) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.hp_icon_red)
                .setContentTitle("Mise à jour refusée")
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
        const val UNIQUE_WORK = "horatrack_update_verification"
        private const val CHANNEL_ID = "hp_update_download"
        private const val NOTIFICATION_ID = 4107

        fun enqueue(context: Context) {
            val appContext = context.applicationContext
            appContext.getSharedPreferences(UpdateChecker.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(UpdateChecker.KEY_VERIFICATION_PENDING, true).apply()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<UpdateVerificationWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
