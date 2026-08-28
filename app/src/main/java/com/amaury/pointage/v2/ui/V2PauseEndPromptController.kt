package com.amaury.pointage.v2.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.amaury.pointage.PauseScheduleManager
import java.util.WeakHashMap
import kotlin.math.max

/** Confirmation temporaire après reprise automatique à la fin d'une pause programmée. */
object V2PauseEndPromptController {
    private val showing = WeakHashMap<Activity, Boolean>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun maybeShow(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || showing[activity] == true) return
        val pending = PauseScheduleManager.pendingEndConfirmation(activity) ?: return
        val remaining = pending.deadlineMs - System.currentTimeMillis()
        if (remaining <= 0L) {
            PauseScheduleManager.clearEndConfirmation(activity)
            return
        }

        showing[activity] = true
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Fin de pause programmée")
            .setMessage(messageFor(remaining))
            .setPositiveButton("OUI, J'AI TERMINÉ") { _, _ ->
                PauseScheduleManager.confirmPauseEnded(activity)
            }
            .setNegativeButton("NON, JE SUIS TOUJOURS EN PAUSE") { _, _ ->
                PauseScheduleManager.confirmStillPaused(activity)
            }
            .setCancelable(false)
            .create()

        lateinit var ticker: Runnable
        ticker = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val left = pending.deadlineMs - System.currentTimeMillis()
                if (left <= 0L) {
                    PauseScheduleManager.clearEndConfirmation(activity)
                    dialog.dismiss()
                    return
                }
                dialog.setMessage(messageFor(left))
                mainHandler.postDelayed(this, 1_000L)
            }
        }

        dialog.setOnDismissListener {
            mainHandler.removeCallbacks(ticker)
            showing.remove(activity)
        }
        dialog.show()
        mainHandler.post(ticker)
    }

    private fun messageFor(remainingMs: Long): String {
        val totalSeconds = max(0L, (remainingMs + 999L) / 1_000L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "HoraTrack a repris automatiquement le temps de travail. As-tu réellement terminé ta pause ?\n\n" +
            "Fermeture automatique dans %d:%02d".format(minutes, seconds)
    }
}
