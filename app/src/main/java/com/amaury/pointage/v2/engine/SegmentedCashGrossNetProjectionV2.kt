package com.amaury.pointage.v2.engine

data class SegmentedCashGrossNetProjectionResultV2(
    val cash: SegmentedCashGrossAssemblyResultV2,
    val projection: EmployeeNetProjectionV2.Result?,
    val cashGrossReliable: Boolean,
    val netBeforeIncomeTaxComplete: Boolean,
    val warnings: List<String>
)

/**
 * Pont unique cashGross segmenté -> moteur net canonique Android.
 *
 * Le plafond social ne reçoit jamais un zéro inventé pour les heures complémentaires :
 * leur valeur et leur fiabilité doivent être fournies explicitement par l'amont.
 */
object SegmentedCashGrossNetProjectionV2 {
    const val CASH_WARNING =
        "Projection nette segmentée : brut en espèces absent ou non fiable."
    const val COMPLEMENTARY_WARNING =
        "Projection nette segmentée : minutes complémentaires non prouvées ; plafond social et net bloqués."

    fun project(
        cash: SegmentedCashGrossAssemblyResultV2,
        year: Int,
        company: CompanyPayrollOverridesV2.Snapshot,
        complementaryMinutes: Int?,
        complementaryMinutesReliable: Boolean
    ): SegmentedCashGrossNetProjectionResultV2 {
        val cashGross = cash.cashGross
        val upstreamWarnings = (cash.warnings + company.warnings).distinct()
        if (!cash.reliable || cashGross == null || !cashGross.isFinite() || cashGross < 0.0) {
            return blocked(cash, upstreamWarnings + CASH_WARNING)
        }
        if (!complementaryMinutesReliable ||
            complementaryMinutes == null ||
            complementaryMinutes < 0
        ) {
            return blocked(cash, upstreamWarnings + COMPLEMENTARY_WARNING)
        }

        val projection = EmployeeNetProjectionV2.calculate(
            gross = cashGross,
            year = year,
            company = company,
            complementaryMinutes = complementaryMinutes,
            upstreamGrossReliable = true
        )
        return SegmentedCashGrossNetProjectionResultV2(
            cash = cash,
            projection = projection,
            cashGrossReliable = projection.payroll.grossReliable,
            netBeforeIncomeTaxComplete = projection.netBeforeIncomeTaxComplete,
            warnings = (upstreamWarnings + projection.warnings).distinct()
        )
    }

    private fun blocked(
        cash: SegmentedCashGrossAssemblyResultV2,
        warnings: List<String>
    ) = SegmentedCashGrossNetProjectionResultV2(
        cash = cash,
        projection = null,
        cashGrossReliable = false,
        netBeforeIncomeTaxComplete = false,
        warnings = warnings.distinct()
    )
}
