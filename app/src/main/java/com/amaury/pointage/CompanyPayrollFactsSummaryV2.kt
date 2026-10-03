package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.CompanyAtMpRateStoreV2
import com.amaury.pointage.v2.CompanyProfessionalStatusStoreV2

/** Résumés d'historiques, sans choisir arbitrairement une période active. */
object CompanyPayrollFactsSummaryV2 {
    fun professionalStatus(context: Context, companyId: String): String {
        val stored = CompanyProfessionalStatusStoreV2.read(context, companyId)
        return when {
            !stored.reliable -> "Statut professionnel : à confirmer — historique indisponible."
            stored.records.isEmpty() -> "Statut professionnel : aucune version datée confirmée."
            else -> "Statut professionnel : ${stored.records.size} version(s) enregistrée(s). La couverture du mois est vérifiée au calcul."
        }
    }

    fun atMp(context: Context, companyId: String): String {
        val stored = CompanyAtMpRateStoreV2.read(context, companyId)
        return when {
            !stored.reliable -> "AT/MP : à confirmer — historique indisponible."
            stored.records.isEmpty() -> "AT/MP : aucune version datée confirmée ; nécessaire pour compléter le coût employeur."
            else -> "AT/MP : ${stored.records.size} version(s) enregistrée(s). Le taux doit couvrir l'établissement et le mois calculés."
        }
    }
}
