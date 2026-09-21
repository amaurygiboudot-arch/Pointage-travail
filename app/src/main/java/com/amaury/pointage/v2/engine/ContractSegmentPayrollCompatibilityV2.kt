package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractV2

/**
 * Décide uniquement si plusieurs versions contractuelles peuvent partager un calcul mensuel unique.
 *
 * Deux versions sont compatibles seulement si tous les paramètres susceptibles d'influencer la
 * paie sont strictement identiques. L'identifiant de version et la source peuvent changer sans
 * modifier le calcul. Dès qu'un paramètre de paie change, aucune proratisation n'est inventée.
 */
data class ContractSegmentPayrollCompatibilityResultV2(
    val contract: ContractV2?,
    val compatibleForSingleMonthlyCalculation: Boolean,
    val changedFields: Set<String>,
    val warnings: List<String>
)

object ContractSegmentPayrollCompatibilityV2 {
    const val EQUIVALENT_VERSIONS_WARNING =
        "Contrat de paie : plusieurs versions datées couvrent le mois mais leurs paramètres de paie sont identiques ; le calcul mensuel unique peut être conservé."
    const val CHANGED_PAYROLL_INPUTS_WARNING =
        "Contrat de paie : un paramètre de paie change en cours de mois ; HoraTrack conserve le temps payé exact par segment mais ne prorate pas le salaire sans règle de proratisation ou planning confirmé."

    fun resolve(segments: List<EmploymentContractCoverageSegmentV2>): ContractSegmentPayrollCompatibilityResultV2 {
        if (segments.isEmpty()) {
            return ContractSegmentPayrollCompatibilityResultV2(
                contract = null,
                compatibleForSingleMonthlyCalculation = false,
                changedFields = emptySet(),
                warnings = emptyList()
            )
        }

        val first = segments.first().snapshot.contract
        val changed = linkedSetOf<String>()
        segments.drop(1).forEach { segment ->
            collectChanges(first, segment.snapshot.contract, changed)
        }
        if (changed.isNotEmpty()) {
            return ContractSegmentPayrollCompatibilityResultV2(
                contract = null,
                compatibleForSingleMonthlyCalculation = false,
                changedFields = changed,
                warnings = listOf(CHANGED_PAYROLL_INPUTS_WARNING)
            )
        }

        return ContractSegmentPayrollCompatibilityResultV2(
            contract = first,
            compatibleForSingleMonthlyCalculation = true,
            changedFields = emptySet(),
            warnings = if (segments.size > 1) listOf(EQUIVALENT_VERSIONS_WARNING) else emptyList()
        )
    }

    private fun collectChanges(a: ContractV2, b: ContractV2, out: MutableSet<String>) {
        if (a.employerId.trim() != b.employerId.trim()) out += "employerId"
        if (a.type != b.type) out += "type"
        if (a.contractualWeeklyMinutes != b.contractualWeeklyMinutes) out += "contractualWeeklyMinutes"
        if (a.grossHourlyRate != b.grossHourlyRate) out += "grossHourlyRate"
        if (a.hireDateEpochDay != b.hireDateEpochDay) out += "hireDateEpochDay"
        if (a.payrollCutoffDay != b.payrollCutoffDay) out += "payrollCutoffDay"
        if (a.forfaitHoursPeriod != b.forfaitHoursPeriod) out += "forfaitHoursPeriod"
        if (a.forfaitHours != b.forfaitHours) out += "forfaitHours"
        if (a.forfaitAnnualDays != b.forfaitAnnualDays) out += "forfaitAnnualDays"
        if (a.monthlyGrossSalary != b.monthlyGrossSalary) out += "monthlyGrossSalary"
    }
}
