package com.amaury.pointage.v2.ui

import android.app.Activity
import android.app.AlertDialog
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2
import java.util.WeakHashMap

/** Affiche au maximum une question par événement GPS réellement ambigu. */
object V2GpsPromptController {
    private val showing = WeakHashMap<Activity, Boolean>()

    fun maybeShow(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed || showing[activity] == true) return
        val pending = GpsWorkStateCoordinatorV2.pending(activity) ?: return
        if (!GpsWorkStateCoordinatorV2.shouldPrompt(activity, pending)) return

        GpsWorkStateCoordinatorV2.markPromptShown(activity, pending)
        showing[activity] = true

        when (pending.kind) {
            GpsWorkStateCoordinatorV2.Pending.Kind.EXIT_WORKSITE -> {
                AlertDialog.Builder(activity)
                    .setTitle("Tu as terminé ta journée ?")
                    .setMessage(
                        "HoraTrack te pose cette question parce que le GPS a détecté une sortie du lieu de travail. " +
                            "Ta réponse détermine si ce moment doit devenir une vraie fin de travail."
                    )
                    .setPositiveButton("OUI") { _, _ ->
                        GpsWorkStateCoordinatorV2.confirmExit(activity, V2RuntimeStore.expectedEnd(activity))
                    }
                    .setNegativeButton("NON") { _, _ ->
                        GpsWorkStateCoordinatorV2.cancelPending(activity)
                    }
                    .setOnCancelListener {
                        // Sans réponse, l'événement reste à confirmer et rien n'est inventé.
                    }
                    .setOnDismissListener { showing.remove(activity) }
                    .show()
            }

            GpsWorkStateCoordinatorV2.Pending.Kind.AMBIGUOUS -> {
                AlertDialog.Builder(activity)
                    .setTitle("Tu es en pause ?")
                    .setMessage(
                        "HoraTrack te pose cette question pour savoir si ce moment doit être compté comme une pause " +
                            "ou rester simplement un déplacement GPS à confirmer."
                    )
                    .setPositiveButton("OUI") { _, _ ->
                        V2RuntimeStore.togglePause(activity, pending.atMs)
                        GpsWorkStateCoordinatorV2.cancelPending(activity)
                    }
                    .setNegativeButton("NON") { _, _ ->
                        GpsWorkStateCoordinatorV2.cancelPending(activity)
                    }
                    .setOnCancelListener {
                        // L'événement reste à confirmer.
                    }
                    .setOnDismissListener { showing.remove(activity) }
                    .show()
            }
        }
    }
}
