package com.amaury.pointage.v2.engine

data class SegmentedSalaryProductionResultV2(
    val worked: SegmentedWorkedGrossProductionResultV2,
    val cash: SegmentedCashGrossAssemblyResultV2,
    val net: SegmentedCashGrossNetProjectionResultV2
) {
    val reliable: Boolean
        get() = worked.reliable && cash.reliable && net.cashGrossReliable

    val warnings: List<String>
        get() = (worked.warnings + cash.warnings + net.warnings).distinct()
}

/**
 * Coordinateur pur de la chaîne segmentée déjà prouvée.
 *
 * Aucune lecture de store et aucune formule locale :
 * B20 riche -> cash gross confirmé -> moteur net canonique.
 */
object SegmentedSalaryProductionV2 {
    fun calculate(
        worked: SegmentedWorkedGrossProductionResultV2,
        fixed: ConfirmedCashGrossComponentsV2,
        year: Int,
        company: CompanyPayrollOverridesV2.Snapshot,
        complementaryMinutes: Int?,
        complementaryMinutesReliable: Boolean
    ): SegmentedSalaryProductionResultV2 {
        val cash = SegmentedCashGrossAssemblerV2.assemble(worked, fixed)
        val net = SegmentedCashGrossNetProjectionV2.project(
            cash = cash,
            year = year,
            company = company,
            complementaryMinutes = complementaryMinutes,
            complementaryMinutesReliable = complementaryMinutesReliable
        )
        return SegmentedSalaryProductionResultV2(worked, cash, net)
    }
}
