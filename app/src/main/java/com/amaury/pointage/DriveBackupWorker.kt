package com.amaury.pointage

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DriveBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {
    override fun doWork(): Result {
        if (!DriveBackupManager.isConfigured(applicationContext)) return Result.success()

        val done = CountDownLatch(1)
        val success = AtomicBoolean(false)
        DriveBackupManager.syncAllAsync(applicationContext) { ok, _ ->
            success.set(ok)
            done.countDown()
        }

        val completed = runCatching { done.await(10, TimeUnit.MINUTES) }.getOrDefault(false)
        return when {
            !completed -> Result.retry()
            success.get() -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK = "horatrack_drive_backup"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<DriveBackupWorker>().build()
            )
        }
    }
}
