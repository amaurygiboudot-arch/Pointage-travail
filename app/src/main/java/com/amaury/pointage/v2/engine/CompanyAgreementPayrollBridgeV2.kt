package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.ApplicableCompanyAgreementRulesV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import java.time.LocalDate

/**
 * Passerelle contrôlée entre les accords d'entreprise et le moteur de paie V2.
 * À ce stade elle expose uniquement les règles applicables : aucun montant n'est modifié automatiquement.
 */
object CompanyAgreementPayrollBridgeV2 {
    data class Snapshot(
        val referenceDate: LocalDate,
        val applicableRules: List<CompanyAgreementRuleStoreV2.StoredCandidate>
    ) {
        val hasApplicableRules: Boolean get() = applicableRules.isNotEmpty()
    }

    fun load(
        context: Context,
        companyId: String,
        referenceDate: LocalDate
    ): Snapshot = Snapshot(
        referenceDate = referenceDate,
        applicableRules = ApplicableCompanyAgreementRulesV2.list(context, companyId, referenceDate)
    )
}
