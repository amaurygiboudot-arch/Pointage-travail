package com.amaury.pointage.v2.engine

import kotlin.math.round

/**
 * Régularise les avances mensuelles RGDU contre le droit annuel calculé.
 *
 * Sécurité : pour le cas annuel standard couvert ici, les 12 mois doivent être présents
 * explicitement, y compris les mois à 0 €. Une absence de mois n'est jamais interprétée comme 0.
 */
object EmployerGeneralReductionAnnualRegularizationV2 {
    data class MonthlyAdvance(
        val month: Int,
        val amount: Double
    )

    data class Result(
        val annualEntitlement: Double?,
        val advancesTotal: Double?,
        /** Positif = réduction complémentaire ; négatif = reprise à régulariser. */
        val adjustment: Double?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        year: Int,
        annual: EmployerGeneralReductionAnnual2026V2.Result,
        monthlyAdvances: List<MonthlyAdvance>
    ): Result {
        if (year != 2026) return blocked("Régularisation RGDU : barème non intégré pour $year.")

        val malformed = monthlyAdvances.any {
            it.month !in 1..12 || !it.amount.isFinite() || it.amount < 0.0
        }
        if (malformed) {
            return blocked("Régularisation RGDU 2026 : une avance mensuelle est invalide.")
        }

        val duplicateMonths = monthlyAdvances.groupingBy { it.month }.eachCount().filterValues { it > 1 }.keys
        if (duplicateMonths.isNotEmpty()) {
            return blocked("Régularisation RGDU 2026 : plusieurs avances existent pour le même mois.")
        }

        val presentMonths = monthlyAdvances.map { it.month }.toSet()
        val expectedMonths = (1..12).toSet()
        if (presentMonths != expectedMonths) {
            val missing = (expectedMonths - presentMonths).sorted().joinToString(", ")
            return blocked(
                "Régularisation RGDU 2026 : les 12 avances mensuelles doivent être connues explicitement ; mois manquants : $missing."
            )
        }

        val annualAmount = annual.amount?.takeIf { annual.reliable && it.isFinite() && it >= 0.0 }
            ?: return blocked(
                annual.warnings.ifEmpty {
                    listOf("Régularisation RGDU 2026 : droit annuel non calculable.")
                }
            )

        val advancesTotal = roundCents(monthlyAdvances.sumOf { it.amount })
        val adjustment = roundCents(annualAmount - advancesTotal)

        return Result(
            annualEntitlement = annualAmount,
            advancesTotal = advancesTotal,
            adjustment = adjustment,
            reliable = true,
            warnings = emptyList()
        )
    }

    private fun roundCents(value: Double): Double = round(value * 100.0) / 100.0

    private fun blocked(message: String) = blocked(listOf(message))

    private fun blocked(warnings: List<String>) = Result(
        annualEntitlement = null,
        advancesTotal = null,
        adjustment = null,
        reliable = false,
        warnings = warnings.distinct()
    )
}
