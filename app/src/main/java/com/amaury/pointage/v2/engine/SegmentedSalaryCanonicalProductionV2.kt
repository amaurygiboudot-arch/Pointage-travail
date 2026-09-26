package com.amaury.pointage.v2.engine

data class SegmentedSalaryCanonicalProductionResultV2(
    val output: SegmentedSalaryCanonicalOutputV2,
    val complementaryMinutesReliable: Boolean
)

/**
 * Coordinateur pur B20 -> cash -> net -> sortie canonique.
 *
 * Aucun store n'est relu et aucune formule métier n'est recréée ici.
 * Les composantes fixes et le contexte social doivent déjà provenir
 * de leurs propriétaires V2.
 */
object SegmentedSalaryCanonicalProductionV2 {
    fun calculate(
        worked: SegmentedWorkedGrossProductionResultV2,
        fixed: ConfirmedCashGrossComponentsV2,
        year: Int,
        companyPayroll: CompanyPayrollOverridesV2.Snapshot
    ): SegmentedSalaryCanonicalProductionResultV2 {
        val complementary = complementaryMinutes(worked)
        val cash = SegmentedCashGrossAssemblerV2.assemble(
            worked = worked,
            fixed = fixed
        )
        val net = SegmentedCashGrossNetProjectionV2.project(
            cash = cash,
            year = year,
            company = companyPayroll,
            complementaryMinutes = complementary.minutes,
            complementaryMinutesReliable = complementary.reliable
        )
        val output = SegmentedSalaryCanonicalOutputAssemblerV2.assemble(
            worked = worked,
            cash = cash,
            net = net
        )
        return SegmentedSalaryCanonicalProductionResultV2(
            output = output,
            complementaryMinutesReliable = complementary.reliable
        )
    }

    private data class ComplementaryMinutes(
        val minutes: Int?,
        val reliable: Boolean
    )

    private fun complementaryMinutes(
        worked: SegmentedWorkedGrossProductionResultV2
    ): ComplementaryMinutes {
        if (!worked.variables.reliable) return ComplementaryMinutes(null, false)
        var total = 0L
        for (breakdown in worked.variables.breakdowns) {
            val value = breakdown.complementaryMinutes
            if (value < 0) return ComplementaryMinutes(null, false)
            total += value.toLong()
            if (total > Int.MAX_VALUE) return ComplementaryMinutes(null, false)
        }
        return ComplementaryMinutes(total.toInt(), true)
    }
}
