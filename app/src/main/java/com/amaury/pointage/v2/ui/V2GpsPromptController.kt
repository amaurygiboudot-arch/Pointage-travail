package com.amaury.pointage.v2.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.GpsTransitionV2
import com.amaury.pointage.v2.engine.GpsWorkStateCoordinatorV2
import java.util.WeakHashMap

/** Affiche au maximum une question à la fois, sans perdre les événements sans réponse. */
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
                    .setNegativeButton("NON") { _, _ -> GpsWorkStateCoordinatorV2.cancelPending(activity) }
                    .setOnCancelListener {
                        GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                    }
                    .setOnDismissListener { showing.remove(activity) }
                    .show()
            }

            GpsWorkStateCoordinatorV2.Pending.Kind.AMBIGUOUS -> {
                val entering = pending.transition == GpsTransitionV2.ENTER

                if (entering) {
                    AlertDialog.Builder(activity)
                        .setTitle("Pause détectée")
                        .setMessage(
                            "HoraTrack a détecté ton arrivée dans cette zone. " +
                                "Si c'est bien le début d'une pause, indique explicitement si elle est payée."
                        )
                        .setPositiveButton("PAUSE PAYÉE") { _, _ ->
                            if (!GpsWorkStateCoordinatorV2.confirmPauseStart(activity, paid = true)) {
                                GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                                Toast.makeText(
                                    activity,
                                    "Pause non enregistrée : vérifie l'état du pointage.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .setNegativeButton("PAUSE NON PAYÉE") { _, _ ->
                            if (!GpsWorkStateCoordinatorV2.confirmPauseStart(activity, paid = false)) {
                                GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                                Toast.makeText(
                                    activity,
                                    "Pause non enregistrée : vérifie l'état du pointage.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .setNeutralButton("PAS UNE PAUSE") { _, _ ->
                            GpsWorkStateCoordinatorV2.cancelPending(activity)
                        }
                        .setOnCancelListener {
                            GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                        }
                        .setOnDismissListener { showing.remove(activity) }
                        .show()
                } else {
                    AlertDialog.Builder(activity)
                        .setTitle("Tu reprends le travail ?")
                        .setMessage(
                            "HoraTrack a détecté ta sortie de cette zone. Confirme si ce déplacement correspond à la reprise du travail."
                        )
                        .setPositiveButton("OUI") { _, _ ->
                            if (!GpsWorkStateCoordinatorV2.confirmPauseEnd(activity)) {
                                GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                                Toast.makeText(
                                    activity,
                                    "Reprise non enregistrée : aucune pause ouverte fiable à terminer.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .setNegativeButton("NON") { _, _ ->
                            GpsWorkStateCoordinatorV2.cancelPending(activity)
                        }
                        .setOnCancelListener {
                            GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                        }
                        .setOnDismissListener { showing.remove(activity) }
                        .show()
                }
            }
        }
    }
}
