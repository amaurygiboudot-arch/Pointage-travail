package com.amaury.pointage

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2
import com.amaury.pointage.v2.model.SessionStatusV2
import java.util.concurrent.TimeUnit

/** Vérification de sortie durable, avec délai anti-rebond, y compris en arrière-plan. */
class GpsExitAutoCloseWorkerV2(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {

    override fun doWork(): Result {
        val id = inputData.getString(KEY_PENDING_ID)?.takeIf { it.isNotBlank() }
            ?: return Result.success()
        val app = applicationContext
        if (GpsWorkStateCoordinatorV2.pending(app)?.id != id) return Result.success()

        if (GpsWorkStateCoordinatorV2.autoConfirmExit(app, id)) {
            GpsExitConfirmationNotificationV2.cancel(app)
            IconSwitcher.sync(app)
            PointageWidgetProvider.updateAll(app)
            QuickActionsWidgetProvider.updateAll(app)
        } else {
            val runtime = V2RuntimeReader.current(app)
            if (runtime.reliable && runtime.snapshot.session?.status != SessionStatusV2.OPEN) {
                GpsWorkStateCoordinatorV2.discardIfNoOpenSession(app, runtime.snapshot.session)
                GpsExitConfirmationNotificationV2.cancel(app)
                IconSwitcher.sync(app)
            } else if (GpsWorkStateCoordinatorV2.pending(app)?.id == id) {
                GpsExitConfirmationNotificationV2.show(app)
                IconSwitcher.sync(app)
            }
        }
        return Result.success()
    }

    companion object {
        private const val KEY_PENDING_ID = "gps_exit_pending_id"
        private const val UNIQUE_WORK = "gps_exit_auto_close_v2"
        fun schedule(context: Context, pendingId: String): Boolean {
            if (pendingId.isBlank()) return false
            return runCatching {
                val work = OneTimeWorkRequestBuilder<GpsExitAutoCloseWorkerV2>()
                    .setInputData(workDataOf(KEY_PENDING_ID to pendingId))
                    .setInitialDelay(2, TimeUnit.MINUTES)
                    .build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, work)
                true
            }.getOrDefault(false)
        }
    }
}
