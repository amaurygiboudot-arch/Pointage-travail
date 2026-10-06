package com.amaury.pointage.v2.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import com.amaury.pointage.v2.engine.GpsExitConfirmationPolicyV2
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
                val session = V2RuntimeStore.snapshot(activity, pending.atMs).session
                if (session == null) {
                    showing.remove(activity)
                    GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                    return
                }
                showExit(activity, pending, session.id, pending.atMs)

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
                            if (!GpsWorkStateCoordinatorV2.confirmPauseStart(
                                    activity,
                                    pending.id,
                                    paid = true
                                )
                            ) {
                                GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                                Toast.makeText(
                                    activity,
                                    "Pause non enregistrée : vérifie l'état du pointage.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .setNegativeButton("PAUSE NON PAYÉE") { _, _ ->
                            if (!GpsWorkStateCoordinatorV2.confirmPauseStart(
                                    activity,
                                    pending.id,
                                    paid = false
                                )
                            ) {
                                GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                                Toast.makeText(
                                    activity,
                                    "Pause non enregistrée : vérifie l'état du pointage.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .setNeutralButton("PAS UNE PAUSE") { _, _ ->
                            GpsWorkStateCoordinatorV2.cancelPending(activity, pending.id)
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
                            if (!GpsWorkStateCoordinatorV2.confirmPauseEnd(activity, pending.id)) {
                                GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                                Toast.makeText(
                                    activity,
                                    "Reprise non enregistrée : aucune pause ouverte fiable à terminer.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .setNegativeButton("NON") { _, _ ->
                            GpsWorkStateCoordinatorV2.cancelPending(activity, pending.id)
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
    private fun showExit(
        activity: Activity,
        pending: GpsWorkStateCoordinatorV2.Pending,
        sessionId: String,
        exitMs: Long
    ) {
        fun label(ms: Long) = SimpleDateFormat("dd/MM/yyyy à HH:mm", Locale.FRANCE).format(ms)
        val counted = V2RuntimeStore.previewCountedExit(activity, exitMs)
        var changingTime = false
        AlertDialog.Builder(activity)
            .setTitle("Tu as terminé ta journée ?")
            .setMessage(
                "Départ détecté par GPS : ${label(pending.atMs)}.\n" +
                    "Départ réel à enregistrer : ${label(exitMs)}.\n" +
                    "Sortie comptée : ${counted?.let { label(it) } ?: "à confirmer"}.\n\n" +
                    "Si le GPS a détecté ton départ en retard, corrige l'heure avant de confirmer."
            )
            .setPositiveButton("OUI, CONFIRMER") { _, _ ->
                if (!GpsWorkStateCoordinatorV2.confirmExit(
                        activity, pending.id, confirmedExitMs = exitMs, expectedSessionId = sessionId
                    )
                ) {
                    GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending)
                    Toast.makeText(activity, "Sortie non enregistrée : vérifie le pointage et les pauses.", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("NON") { _, _ ->
                GpsWorkStateCoordinatorV2.cancelPending(activity, pending.id)
            }
            .setNeutralButton("CORRIGER L'HEURE") { _, _ ->
                changingTime = true
                pickExitTime(activity, pending, sessionId, exitMs)
            }
            .setOnCancelListener { GpsWorkStateCoordinatorV2.allowPromptAgain(activity, pending) }
            .setOnDismissListener { if (!changingTime) showing.remove(activity) }
            .show()
    }

    private fun pickExitTime(
        activity: Activity,
        pending: GpsWorkStateCoordinatorV2.Pending,
        sessionId: String,
        previousExitMs: Long
    ) {
        val calendar = Calendar.getInstance().apply { timeInMillis = previousExitMs }
        val dateDialog = DatePickerDialog(activity, { _, year, month, day ->
            calendar.set(year, month, day)
            TimePickerDialog(activity, { _, hour, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hour)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val candidate = calendar.timeInMillis
                val session = V2RuntimeStore.snapshot(activity, pending.atMs).session
                val valid = session != null && GpsExitConfirmationPolicyV2.canConfirm(
                    session, sessionId, pending.atMs, candidate
                )
                if (!valid) {
                    Toast.makeText(activity, "Heure incompatible avec l'entrée, les pauses ou le départ détecté.", Toast.LENGTH_LONG).show()
                }
                showExit(activity, pending, sessionId, if (valid) candidate else previousExitMs)
            }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).apply {
                setOnCancelListener { showExit(activity, pending, sessionId, previousExitMs) }
            }.show()
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH))
        dateDialog.setOnCancelListener { showExit(activity, pending, sessionId, previousExitMs) }
        dateDialog.show()
    }

}
