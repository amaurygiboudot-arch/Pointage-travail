package com.amaury.pointage.v2.engine

data class SegmentedSalaryCanonicalOutputV2(
    val worked: SegmentedWorkedGrossProductionResultV2,
    val cash: SegmentedCashGrossAssemblyResultV2,
    val net: SegmentedCashGrossNetProjectionResultV2,
    val paidMinutes: Int?,
    val variableOvertimeMinutes: Int?,
    val structuralOvertimeMinutes: Double?,
    val totalOvertimeMinutes: Double?,
    val complementaryMinutes: Int?,
    val nightMinutes: Int?,
    val saturdayMinutes: Int?,
    val sundayMinutes: Int?,
    val publicHolidayMinutes: Int?,
    val baseGross: Double?,
    /** Brut HS variables uniquement. */
    val overtimeGross: Double?,
    val structuralOvertimeGross: Double?,
    val totalOvertimeGross: Double?,
    val complementaryGross: Double?,
    val premiumGross: Double?,
    val workedGross: Double?,
    val cashGross: Double?,
    val netBeforeIncomeTax: Double?,
    val netTaxable: Double?,
    val incomeTax: Double?,
    val netAfterIncomeTax: Double?,
    val paidTimeReliable: Boolean,
    val premiumTimeReliable: Boolean,
    val workedGrossReliable: Boolean,
    val cashGrossReliable: Boolean,
    val netBeforeIncomeTaxComplete: Boolean,
    val warnings: List<String>
)

/**
 * Vue canonique commune des résultats déjà produits par B21/B20/cash/net.
 *
 * Cette couche ne calcule aucun droit ni taux. Elle agrège uniquement des résultats prouvés,
 * afin que écran, PDF, comparaison et historique puissent consommer la même sortie.
 */
object SegmentedSalaryCanonicalOutputAssemblerV2 {
    const val CHAIN_WARNING =
        "Sortie Salaire segmentée : les résultats B20, cash gross et net ne proviennent pas de la même chaîne."
    const val TIME_WARNING =
        "Sortie Salaire segmentée : le temps payé ne peut pas être agrégé de façon fiable."
    const val PREMIUM_TIME_WARNING =
        "Sortie Salaire segmentée : la ventilation nuit/samedi/dimanche/jour férié n'est pas fiable."
    const val VARIABLE_WARNING =
        "Sortie Salaire segmentée : la ventilation des variables de brut n'est pas fiable."

    fun assemble(
        worked: SegmentedWorkedGrossProductionResultV2,
        cash: SegmentedCashGrossAssemblyResultV2,
        net: SegmentedCashGrossNetProjectionResultV2
    ): SegmentedSalaryCanonicalOutputV2 {
        val warnings = mutableListOf<String>()
        warnings += worked.warnings
        warnings += cash.warnings
        warnings += net.warnings

        val workedGross = worked.workedGross
        val chainConsistent =
            net.cash == cash &&
                sameMoney(cash.workedGross, workedGross)

        if (!chainConsistent) warnings += CHAIN_WARNING

        val weeks = worked.evidence.slices.flatMap { it.weeks }
        val weekKeys = weeks.map { it.weekYear to it.weekOfYear }
        val uniqueWeeks = weekKeys.distinct().size == weekKeys.size
        val timeInputsValid =
            worked.evidence.reliable &&
                uniqueWeeks &&
                weeks.all { it.week.paidMinutes >= 0 }

        val paidMinutes = if (timeInputsValid) sumMinutes(weeks.map { it.week.paidMinutes }) else null
        val paidTimeReliable = paidMinutes != null
        if (!paidTimeReliable) warnings += TIME_WARNING

        val premiumInputsValid =
            paidTimeReliable &&
                worked.evidence.slices.all { it.premiumTimeBreakdownReliable } &&
                weeks.all {
                    it.week.nightMinutes >= 0 &&
                        it.week.saturdayMinutes >= 0 &&
                        it.week.sundayMinutes >= 0 &&
                        it.week.publicHolidayMinutes >= 0
                }
        val nightMinutes = if (premiumInputsValid) sumMinutes(weeks.map { it.week.nightMinutes }) else null
        val saturdayMinutes = if (premiumInputsValid) sumMinutes(weeks.map { it.week.saturdayMinutes }) else null
        val sundayMinutes = if (premiumInputsValid) sumMinutes(weeks.map { it.week.sundayMinutes }) else null
        val publicHolidayMinutes = if (premiumInputsValid) sumMinutes(weeks.map { it.week.publicHolidayMinutes }) else null
        val premiumTimeReliable =
            premiumInputsValid &&
                nightMinutes != null &&
                saturdayMinutes != null &&
                sundayMinutes != null &&
                publicHolidayMinutes != null
        if (!premiumTimeReliable) warnings += PREMIUM_TIME_WARNING

        val variableBreakdownReliable =
            worked.variables.reliable &&
                worked.variables.breakdowns.all {
                    finiteNonNegative(it.overtimeGross) &&
                        finiteNonNegative(it.complementaryGross) &&
                        finiteNonNegative(it.premiumGross) &&
                        it.variableOvertimeMinutes >= 0 &&
                        it.complementaryMinutes >= 0
                }
        val overtimeGross = if (variableBreakdownReliable) sumMoney(worked.variables.breakdowns.map { it.overtimeGross }) else null
        val complementaryGross = if (variableBreakdownReliable) sumMoney(worked.variables.breakdowns.map { it.complementaryGross }) else null
        val premiumGross = if (variableBreakdownReliable) sumMoney(worked.variables.breakdowns.map { it.premiumGross }) else null
        val variableOvertimeMinutes = if (variableBreakdownReliable) {
            sumMinutes(worked.variables.breakdowns.map { it.variableOvertimeMinutes })
        } else null
        val complementaryMinutes = if (variableBreakdownReliable) {
            sumMinutes(worked.variables.breakdowns.map { it.complementaryMinutes })
        } else null
        if (!variableBreakdownReliable || overtimeGross == null || complementaryGross == null ||
            premiumGross == null || variableOvertimeMinutes == null || complementaryMinutes == null
        ) {
            warnings += VARIABLE_WARNING
        }

        val structuralBreakdownReliable =
            worked.base.reliable &&
                worked.base.pieces.isNotEmpty() &&
                worked.base.pieces.all {
                    finiteNonNegative(it.proratedStructuralOvertimeMinutes) &&
                        finiteNonNegative(it.proratedStructuralOvertimeGross)
                }
        val structuralOvertimeMinutes = if (structuralBreakdownReliable) {
            sumDecimal(worked.base.pieces.map { it.proratedStructuralOvertimeMinutes })
        } else null
        val structuralOvertimeGross = if (structuralBreakdownReliable) {
            sumMoney(worked.base.pieces.map { it.proratedStructuralOvertimeGross })
        } else null
        val totalOvertimeMinutes =
            structuralOvertimeMinutes?.let { structural ->
                variableOvertimeMinutes?.let { variable ->
                    safeAdd(structural, variable.toDouble())
                }
            }
        val totalOvertimeGross =
            structuralOvertimeGross?.let { structural ->
                overtimeGross?.let { variable -> safeAdd(structural, variable) }
            }

        val baseGross = worked.base.baseGross?.takeIf { worked.base.reliable && finiteNonNegative(it) }
        val reliableWorkedGross = workedGross?.takeIf {
            chainConsistent && worked.reliable && worked.assembly.reliable && finiteNonNegative(it)
        }
        val reliableCashGross = cash.cashGross?.takeIf {
            chainConsistent && cash.reliable && finiteNonNegative(it)
        }

        val projection = net.projection
        val netComplete =
            chainConsistent &&
                net.netBeforeIncomeTaxComplete &&
                projection?.netBeforeIncomeTax != null

        return SegmentedSalaryCanonicalOutputV2(
            worked = worked,
            cash = cash,
            net = net,
            paidMinutes = paidMinutes,
            variableOvertimeMinutes = variableOvertimeMinutes,
            structuralOvertimeMinutes = structuralOvertimeMinutes,
            totalOvertimeMinutes = totalOvertimeMinutes,
            complementaryMinutes = complementaryMinutes,
            nightMinutes = nightMinutes,
            saturdayMinutes = saturdayMinutes,
            sundayMinutes = sundayMinutes,
            publicHolidayMinutes = publicHolidayMinutes,
            baseGross = baseGross,
            overtimeGross = overtimeGross,
            structuralOvertimeGross = structuralOvertimeGross,
            totalOvertimeGross = totalOvertimeGross,
            complementaryGross = complementaryGross,
            premiumGross = premiumGross,
            workedGross = reliableWorkedGross,
            cashGross = reliableCashGross,
            netBeforeIncomeTax = projection?.netBeforeIncomeTax.takeIf { netComplete },
            netTaxable = projection?.netTaxable.takeIf { netComplete },
            incomeTax = projection?.incomeTax.takeIf { netComplete },
            netAfterIncomeTax = projection?.netAfterIncomeTax.takeIf { netComplete },
            paidTimeReliable = paidTimeReliable,
            premiumTimeReliable = premiumTimeReliable,
            workedGrossReliable = reliableWorkedGross != null,
            cashGrossReliable = reliableCashGross != null,
            netBeforeIncomeTaxComplete = netComplete,
            warnings = warnings.distinct()
        )
    }

    private fun sumMinutes(values: List<Int>): Int? {
        var total = 0L
        for (value in values) {
            total += value.toLong()
            if (total > Int.MAX_VALUE) return null
        }
        return total.toInt()
    }

    private fun sumMoney(values: List<Double>): Double? = sumDecimal(values)

    private fun sumDecimal(values: List<Double>): Double? {
        var total = 0.0
        for (value in values) {
            if (!finiteNonNegative(value)) return null
            total += value
            if (!total.isFinite() || total < 0.0) return null
        }
        return total
    }

    private fun safeAdd(left: Double, right: Double): Double? {
        if (!finiteNonNegative(left) || !finiteNonNegative(right)) return null
        val total = left + right
        return total.takeIf(::finiteNonNegative)
    }

    private fun sameMoney(left: Double?, right: Double?): Boolean =
        left != null && right != null &&
            finiteNonNegative(left) && finiteNonNegative(right) &&
            kotlin.math.abs(left - right) <= 0.005

    private fun finiteNonNegative(value: Double): Boolean =
        value.isFinite() && value >= 0.0
}
