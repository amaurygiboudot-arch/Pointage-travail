package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.ApplicableCompanyAgreementRulesV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import java.time.LocalDate

/**
 * Passerelle contrôlée entre les accords d'entreprise et le moteur de paie V2.
 * Les règles applicables restent visibles, mais seules les valeurs explicitement validées
 * sont exposées comme prêtes au calcul. Aucun montant n'est encore modifié ici.
 */
object CompanyAgreementPayrollBridgeV2 {
    data class Snapshot(
        val referenceDate: LocalDate,
        val applicableRules: List<CompanyAgreementRuleStoreV2.StoredCandidate>,
        val calculationReadyRules: List<CompanyAgreementStructuredRuleV2.Rule>
    ) {
        val hasApplicableRules: Boolean get() = applicableRules.isNotEmpty()
        val hasCalculationReadyRules: Boolean get() = calculationReadyRules.isNotEmpty()
    }

    fun load(
        context: Context,
        companyId: String,
        referenceDate: LocalDate
    ): Snapshot {
        val applicable = ApplicableCompanyAgreementRulesV2.list(context, companyId, referenceDate)
        return Snapshot(
            referenceDate = referenceDate,
            applicableRules = applicable,
            calculationReadyRules = applicable
                .map(CompanyAgreementStructuredRuleV2::structure)
                .filter { it.calculationReady }
        )
    }
}
