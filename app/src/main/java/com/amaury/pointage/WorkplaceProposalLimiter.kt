package com.amaury.pointage

import android.app.Activity
import android.content.Context

/**
 * Évite que la détection intelligente devienne intrusive.
 * Une proposition de lieu de travail ne peut être affichée que deux fois maximum
 * pour une même zone candidate. Après la deuxième tentative, HoraTrack n'insiste plus.
 */
object WorkplaceProposalLimiter {
    private const val PREFS = "smart_setup"
    private const val MAX_PROPOSALS_PER_ZONE = 2

    fun showIfAllowed(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val zoneId = prefs.getString("pending_workplace_zone", "").orEmpty()
        if (zoneId.isBlank()) return

        val countKey = "proposal_count_$zoneId"
        val count = prefs.getInt(countKey, 0)
        if (count >= MAX_PROPOSALS_PER_ZONE) {
            prefs.edit()
                .remove("pending_workplace_zone")
                .remove("pending_workplace_address")
                .remove("pending_workplace_company")
                .putBoolean("proposal_dialog_visible", false)
                .putBoolean("proposal_silenced_$zoneId", true)
                .apply()
            return
        }

        // On compte l'affichage, pas seulement la réponse : fermer deux fois suffit
        // pour que l'application comprenne qu'il ne faut plus insister.
        prefs.edit().putInt(countKey, count + 1).apply()
        SmartSetupManager.showPendingWorkplaceProposal(activity)
    }
}
