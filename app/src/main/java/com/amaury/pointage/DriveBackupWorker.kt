package com.amaury.pointage

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Reprise après arrêt du processus : WorkManager conserve la demande sur disque. */
class DriveBackupWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {
    override fun doWork(): Result = when (
        DriveBackupWorkPolicy.run(
            configured = { DriveBackupManager.isConfigured(applicationContext) },
            stopped = { isStopped || Thread.currentThread().isInterrupted },
            attempt = runAttemptCount,
            sync = { DriveBackupManager.syncAllBlocking(applicationContext) {
                isStopped || Thread.currentThread().isInterrupted
            } }
        )
    ) {
        DriveBackupWorkPolicy.Outcome.SUCCESS -> Result.success()
        DriveBackupWorkPolicy.Outcome.RETRY -> Result.retry()
        DriveBackupWorkPolicy.Outcome.FAILURE -> Result.failure()
    }

    companion object {
        internal const val UNIQUE_WORK = "horatrack_drive_backup"

        fun enqueue(context: Context): Operation =
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DriveBackupWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build()
            )

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
        }
    }
}
