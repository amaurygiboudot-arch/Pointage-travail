package com.amaury.pointage.v2.engine

data class SegmentedSalaryProductionResultV2(
    val worked: SegmentedWorkedGrossProductionResultV2,
    val fixed: ConfirmedCashGrossComponentsV2,
    val cash: SegmentedCashGrossAssemblyResultV2,
    val net: SegmentedCashGrossNetProjectionResultV2
) {
    val cashGrossReliable: Boolean
        get() = worked.reliable && fixed.exhaustive && cash.reliable && net.cashGrossReliable
    val netComplete: Boolean
        get() = cashGrossReliable && net.netBeforeIncomeTaxComplete
    val warnings: List<String>
        get() = (worked.warnings + fixed.warnings + cash.warnings + net.warnings).distinct()
}

/**
 * Chaîne pure après B20 : composantes fixes confirmées -> cash gross -> net canonique.
 * Aucun store, écran, PDF ou fallback legacy n'est lu ici.
 */
object SegmentedSalaryProductionV2 {
    fun calculate(
        worked: SegmentedWorkedGrossProductionResultV2,
        fixedFacts: List<ConfirmedCashGrossFixedFactV2>,
        fixedExhaustive: Boolean,
        fixedSourceId: String,
        fixedWarnings: List<String> = emptyList(),
        year: Int,
        companyPayroll: CompanyPayrollOverridesV2.Snapshot,
        complementaryMinutes: Int?,
        complementaryMinutesReliable: Boolean
    ): SegmentedSalaryProductionResultV2 {
        val fixed = ConfirmedCashGrossComponentsResolverV2.resolve(
            facts = fixedFacts,
            exhaustive = fixedExhaustive,
            sourceId = fixedSourceId,
            warnings = fixedWarnings
        )
        val cash = SegmentedCashGrossAssemblerV2.assemble(worked, fixed)
        val net = SegmentedCashGrossNetProjectionV2.project(
            cash = cash,
            year = year,
            company = companyPayroll,
            complementaryMinutes = complementaryMinutes,
            complementaryMinutesReliable = complementaryMinutesReliable
        )
        return SegmentedSalaryProductionResultV2(
            worked = worked,
            fixed = fixed,
            cash = cash,
            net = net
        )
    }
}
