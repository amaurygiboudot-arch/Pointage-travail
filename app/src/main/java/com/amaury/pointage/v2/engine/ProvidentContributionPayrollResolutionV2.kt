package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.OfficialAccoProvidentContributionParserV2
import com.amaury.pointage.v2.V2ConventionProvidentContributionBridge
import java.time.LocalDate

/**
 * Convertit l'arbitrage juridique ACCO/KALI en montants de cotisation utilisables par la paie.
 *
 * Cette couche reste volontairement pure et fail-closed : un conflit, une revue juridique requise,
 * une règle sélectionnée sans montant KALI fiable ou une règle ACCO non supportée donnent des
 * montants null. Aucun zéro n'est inventé et aucune retenue réelle de bulletin n'est remplacée ici.
 */
object ProvidentContributionPayrollResolutionV2 {
    data class Result(
        val employeeAmount: Double?,
        val employerAmount: Double?,
        val reliable: Boolean,
        val selectedSource: PayrollLegalArbitratorV2.Source?,
        val warnings: List<String>
    )

    fun resolve(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        branch: V2ConventionProvidentContributionBridge.Snapshot,
        companyRules: List<OfficialAccoProvidentContributionParserV2.Rule>,
        gross: Double,
        companyGuaranteesEquivalent: Boolean? = null,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ): Result {
        val safeGross = gross.takeIf { it.isFinite() && it >= 0.0 }
            ?: return blocked("Prévoyance ACCO/KALI : salaire brut invalide ; calcul juridique bloqué.")

        val arbitration = ProvidentContributionLegalArbitrationBridgeV2.resolve(
            profile = profile,
            referenceDate = referenceDate,
            branch = branch,
            companyRules = companyRules,
            companyGuaranteesEquivalent = companyGuaranteesEquivalent,
            sourceKnowledge = sourceKnowledge
        )

        if (arbitration.resolution.state != PayrollLegalArbitratorV2.State.RESOLVED) {
            return Result(
                employeeAmount = null,
                employerAmount = null,
                reliable = false,
                selectedSource = null,
                warnings = arbitration.warnings
            )
        }

        arbitration.selectedCompanyRule?.let { rule ->
            if (rule.basis != OfficialAccoProvidentContributionParserV2.Basis.GROSS_SALARY) {
                return blocked(
                    "Prévoyance ACCO : assiette sélectionnée non prise en charge par le calcul automatique.",
                    arbitration.warnings
                )
            }
            if (!rule.employeeRate.isFinite() || !rule.employerRate.isFinite() ||
                rule.employeeRate !in 0.0..1.0 || rule.employerRate !in 0.0..1.0 ||
                rule.employeeRate + rule.employerRate <= 0.0
            ) {
                return blocked(
                    "Prévoyance ACCO : taux sélectionnés invalides ; aucun montant n'est calculé.",
                    arbitration.warnings
                )
            }
            return Result(
                employeeAmount = safeGross * rule.employeeRate,
                employerAmount = safeGross * rule.employerRate,
                reliable = true,
                selectedSource = PayrollLegalArbitratorV2.Source.ACCO,
                warnings = arbitration.warnings
            )
        }

        arbitration.selectedBranchRule?.let { selectedRule ->
            val branchResult = branch.result
            val sameRule = branchResult.selectedRule?.ruleId == selectedRule.ruleId
            if (!sameRule || !branchResult.reliable ||
                branchResult.employeeAmount == null || branchResult.employerAmount == null
            ) {
                return blocked(
                    "Prévoyance KALI : règle arbitrée sans montants calculés fiables correspondants ; calcul bloqué.",
                    arbitration.warnings + branchResult.warnings
                )
            }
            return Result(
                employeeAmount = branchResult.employeeAmount,
                employerAmount = branchResult.employerAmount,
                reliable = true,
                selectedSource = PayrollLegalArbitratorV2.Source.KALI,
                warnings = (arbitration.warnings + branchResult.warnings).distinct()
            )
        }

        return blocked(
            "Prévoyance ACCO/KALI : arbitrage résolu sans règle calculable sélectionnée.",
            arbitration.warnings
        )
    }

    private fun blocked(reason: String, extraWarnings: List<String> = emptyList()) = Result(
        employeeAmount = null,
        employerAmount = null,
        reliable = false,
        selectedSource = null,
        warnings = (listOf(reason) + extraWarnings).distinct()
    )
}
