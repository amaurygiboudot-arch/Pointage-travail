package com.amaury.pointage.v2.engine

data class SegmentedCashGrossAdjustmentV2(
    val id: String,
    val label: String,
    val grossDelta: Double,
    val reliable: Boolean,
    val warnings: List<String> = emptyList()
)

data class SegmentedCashGrossAdjustmentSourceV2(
    val sourceId: String,
    val reliable: Boolean,
    val exhaustive: Boolean,
    val adjustments: List<SegmentedCashGrossAdjustmentV2>,
    val warnings: List<String> = emptyList()
)

data class SegmentedFinalCashGrossResultV2(
    val workedGross: Double?,
    val adjustmentGross: Double?,
    val cashGross: Double?,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Assemble B20 avec les seuls ajustements de brut explicitement confirmés.
 *
 * Les avantages en nature restent hors de ce résultat : le moteur social les ajoute ensuite
 * à l'assiette de cotisations. Une source non exhaustive ne devient jamais implicitement zéro.
 */
object SegmentedFinalCashGrossV2 {
    const val SOURCE_WARNING =
        "Brut final : les ajustements de brut du mois ne sont pas confirmés exhaustifs."
    const val ADJUSTMENT_WARNING =
        "Brut final : un ajustement de brut est invalide, dupliqué ou non fiable."
    const val AMOUNT_WARNING =
        "Brut final : le montant résultant est invalide."

    fun assemble(
        worked: SegmentedWorkedGrossAssemblyResultV2,
        adjustments: SegmentedCashGrossAdjustmentSourceV2
    ): SegmentedFinalCashGrossResultV2 {
        val warnings = mutableListOf<String>()
        warnings += worked.warnings
        warnings += adjustments.warnings

        val workedGross = worked.workedGross
        if (!worked.reliable || workedGross == null || !workedGross.isFinite() || workedGross < 0.0) {
            warnings += SegmentedWorkedGrossAssemblerV2.AMOUNT_WARNING
            return blocked(warnings)
        }

        if (!adjustments.reliable || !adjustments.exhaustive || adjustments.sourceId.trim().isBlank()) {
            warnings += SOURCE_WARNING
            return blocked(warnings, workedGross)
        }

        val ids = adjustments.adjustments.map { it.id.trim() }
        if (ids.any { it.isBlank() } || ids.distinct().size != ids.size ||
            adjustments.adjustments.any { !it.reliable || !it.grossDelta.isFinite() }
        ) {
            warnings += adjustments.adjustments.flatMap { it.warnings }
            warnings += ADJUSTMENT_WARNING
            return blocked(warnings, workedGross)
        }

        warnings += adjustments.adjustments.flatMap { it.warnings }
        val delta = adjustments.adjustments.sumOf { it.grossDelta }
        val cash = workedGross + delta
        if (!delta.isFinite() || !cash.isFinite() || cash < 0.0) {
            warnings += AMOUNT_WARNING
            return blocked(warnings, workedGross)
        }

        return SegmentedFinalCashGrossResultV2(
            workedGross = workedGross,
            adjustmentGross = delta,
            cashGross = cash,
            reliable = true,
            warnings = warnings.distinct()
        )
    }

    private fun blocked(
        warnings: List<String>,
        workedGross: Double? = null
    ) = SegmentedFinalCashGrossResultV2(
        workedGross = workedGross,
        adjustmentGross = null,
        cashGross = null,
        reliable = false,
        warnings = warnings.distinct()
    )
}
