package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.ApplicableCompanyAgreementRulesV2
import com.amaury.pointage.v2.CompanyAgreementRuleExtractorV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import java.time.LocalDate

/**
 * Passerelle contrôlée entre les accords d'entreprise et le moteur de paie V2.
 * Les règles applicables restent visibles, mais seules les valeurs explicitement validées
 * et sans conflit sont exposées comme sûres pour le futur calcul. Aucun montant n'est encore modifié ici.
 */
object CompanyAgreementPayrollBridgeV2 {
    data class Snapshot(
        val referenceDate: LocalDate,
        val applicableRules: List<CompanyAgreementRuleStoreV2.StoredCandidate>,
        val calculationReadyRules: List<CompanyAgreementStructuredRuleV2.Rule>,
        val overtimePercentRules: List<CompanyAgreementStructuredRuleV2.Rule>,
        val overtimeRules: List<CompanyAgreementOvertimeRuleV2.Rule>,
        val safeOvertimeRules: List<CompanyAgreementOvertimeRuleV2.Rule>,
        val conflictingOvertimeRules: List<CompanyAgreementOvertimeRuleV2.Rule>
    ) {
        val hasApplicableRules: Boolean get() = applicableRules.isNotEmpty()
        val hasCalculationReadyRules: Boolean get() = calculationReadyRules.isNotEmpty()
        val hasOvertimePercentRules: Boolean get() = overtimePercentRules.isNotEmpty()
        val hasOvertimeRules: Boolean get() = overtimeRules.isNotEmpty()
        val hasSafeOvertimeRules: Boolean get() = safeOvertimeRules.isNotEmpty()
        val hasOvertimeConflicts: Boolean get() = conflictingOvertimeRules.isNotEmpty()
    }

    fun load(
        context: Context,
        companyId: String,
        referenceDate: LocalDate
    ): Snapshot {
        val applicable = ApplicableCompanyAgreementRulesV2.list(context, companyId, referenceDate)
        val calculationReady = applicable
            .map(CompanyAgreementStructuredRuleV2::structure)
            .filter { it.calculationReady }
        val overtimePercent = calculationReady.filter { rule ->
            rule.source.category == CompanyAgreementRuleExtractorV2.Category.OVERTIME &&
                rule.value?.type == CompanyAgreementStructuredRuleV2.ValueType.PERCENT
        }
        val overtime = overtimePercent.mapNotNull(CompanyAgreementOvertimeRuleV2::from)
        val conflictCheck = CompanyAgreementOvertimeConflictV2.check(overtime)
        return Snapshot(
            referenceDate = referenceDate,
            applicableRules = applicable,
            calculationReadyRules = calculationReady,
            overtimePercentRules = overtimePercent,
            overtimeRules = overtime,
            safeOvertimeRules = conflictCheck.safeRules,
            conflictingOvertimeRules = conflictCheck.conflictingRules
        )
    }
}
