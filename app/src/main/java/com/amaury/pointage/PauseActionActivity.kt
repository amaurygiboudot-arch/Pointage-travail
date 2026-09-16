package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.PauseActionPolicyV2
import com.amaury.pointage.v2.V2RuntimeReader
import com.amaury.pointage.v2.V2RuntimeStore

/**
 * Point d'entrée unique des pauses manuelles V2 depuis l'application et les widgets.
 *
 * Une nouvelle pause exige toujours un choix explicite payé / non payé. Une pause déjà ouverte
 * est refermée sans redemander son statut : V2RuntimeStore réutilise alors la valeur canonique
 * enregistrée à l'ouverture.
 */
class PauseActionActivity : Activity() {
    private var dialogVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!HoraTrackV2.ENABLED) {
            finish()
            return
        }
        handlePauseAction()
    }

    private fun handlePauseAction() {
        val read = V2RuntimeReader.current(this)
        if (!read.reliable) {
            Toast.makeText(this, "Pause bloquée : données HoraTrack à vérifier", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val session = read.snapshot.session
        val openPauseCount = session?.pauses?.count { it.endMs == null } ?: 0
        val next = PauseActionPolicyV2.next(
            hasOpenSession = session != null && session.realExitMs == null,
            openPauseCount = openPauseCount
        )

        when (next) {
            PauseActionPolicyV2.Next.NO_OPEN_SESSION -> {
                Toast.makeText(this, "Aucune entrée en cours", Toast.LENGTH_SHORT).show()
                finish()
            }
            PauseActionPolicyV2.Next.CLOSE_EXISTING -> closeExistingPause()
            PauseActionPolicyV2.Next.SELECT_PAID_STATUS -> showPaidStatusChoice()
            PauseActionPolicyV2.Next.INVALID_MULTIPLE_OPEN_PAUSES -> {
                Toast.makeText(this, "Pause bloquée : état HoraTrack incohérent", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun closeExistingPause() {
        val changed = V2RuntimeStore.togglePause(this)
        Toast.makeText(
            this,
            if (changed) "Travail repris" else "Pause non modifiée : statut à vérifier",
            if (changed) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        ).show()
        if (changed) refreshWidgets()
        finish()
    }

    private fun showPaidStatusChoice() {
        if (dialogVisible || isFinishing || isDestroyed) return
        dialogVisible = true
        AlertDialog.Builder(this)
            .setTitle("Démarrer une pause")
            .setMessage("Cette pause est-elle rémunérée ?")
            .setPositiveButton("PAYÉE") { _, _ -> startPause(paid = true) }
            .setNegativeButton("NON PAYÉE") { _, _ -> startPause(paid = false) }
            .setNeutralButton("ANNULER") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .setOnDismissListener { dialogVisible = false }
            .show()
    }

    private fun startPause(paid: Boolean) {
        val changed = V2RuntimeStore.togglePause(this, paid = paid)
        Toast.makeText(
            this,
            if (changed) "Pause démarrée" else "Impossible de démarrer la pause",
            if (changed) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        ).show()
        if (changed) refreshWidgets()
        finish()
    }

    private fun refreshWidgets() {
        PointageWidgetProvider.updateAll(this)
        QuickActionsWidgetProvider.updateAll(this)
    }
}
