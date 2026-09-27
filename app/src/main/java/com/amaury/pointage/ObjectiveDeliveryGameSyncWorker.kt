package com.amaury.pointage

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Vide la sauvegarde en attente du mini-jeu lorsque le réseau redevient disponible.
 * Le Worker ne fait progresser aucun temps de jeu : il ne fait que synchroniser
 * l'état déjà sauvegardé localement.
 */
class ObjectiveDeliveryGameSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val typeId = inputData.getString(KEY_COMPANY_TYPE)
            ?: return Result.failure()
        val type = ObjectiveCompanyType.entries
            .firstOrNull { it.id == typeId }
            ?: return Result.failure()

        val latch = CountDownLatch(1)
        var synced = false

        ObjectiveDeliveryGameStore.syncPending(
            applicationContext,
            type
        ) { success ->
            synced = success
            latch.countDown()
        }

        val completed = runCatching {
            latch.await(SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)

        return when {
            !completed -> Result.retry()
            synced -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val KEY_COMPANY_TYPE = "company_type"
        private const val SYNC_TIMEOUT_SECONDS = 25L

        fun enqueue(context: Context, type: ObjectiveCompanyType) {
            val appContext = context.applicationContext
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ObjectiveDeliveryGameSyncWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_COMPANY_TYPE, type.id)
                        .build()
                )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(appContext).enqueueUniqueWork(
                uniqueWorkName(type),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        internal fun uniqueWorkName(type: ObjectiveCompanyType): String =
            "objective_delivery_sync_" + type.id
    }
}
