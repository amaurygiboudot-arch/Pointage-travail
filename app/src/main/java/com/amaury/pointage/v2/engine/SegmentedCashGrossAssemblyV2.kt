package com.amaury.pointage.v2.engine

data class ConfirmedCashGrossComponentV2(
    val id: String,
    val amount: Double,
    val reliable: Boolean,
    val warnings: List<String> = emptyList()
)

data class ConfirmedCashGrossComponentsV2(
    val components: List<ConfirmedCashGrossComponentV2>,
    val exhaustive: Boolean,
    val sourceId: String,
    val warnings: List<String> = emptyList()
)

data class SegmentedCashGrossAssemblyResultV2(
    val workedGross: Double?,
    val additionalCashGross: Double?,
    val cashGross: Double?,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * B20 -> brut en espèces final.
 *
 * Les composantes fixes sont reçues déjà résolues par leurs stores propriétaires.
 * Une liste vide ne signifie 0 que si exhaustive=true.
 * Les avantages en nature restent hors cashGross : le moteur net les ajoute au brut social.
 */
object SegmentedCashGrossAssemblerV2 {
    const val WORKED_WARNING =
        "Brut en espèces segmenté : brut de travail B20 absent ou non fiable."
    const val COVERAGE_WARNING =
        "Brut en espèces segmenté : liste des composantes fixes non confirmée exhaustive."
    const val COMPONENT_WARNING =
        "Brut en espèces segmenté : composante fixe absente, dupliquée, non fiable ou invalide."
    const val AMOUNT_WARNING =
        "Brut en espèces segmenté : montant non fini, négatif ou total non représentable."

    fun assemble(
        worked: SegmentedWorkedGrossProductionResultV2,
        fixed: ConfirmedCashGrossComponentsV2
    ): SegmentedCashGrossAssemblyResultV2 {
        val upstream = (worked.warnings + fixed.warnings).distinct()
        val workedGross = worked.workedGross
        if (!worked.reliable || !worked.assembly.reliable ||
            workedGross == null || !workedGross.isFinite() || workedGross < 0.0
        ) return blocked(upstream + WORKED_WARNING)

        if (!fixed.exhaustive || fixed.sourceId.isBlank()) {
            return blocked(upstream + COVERAGE_WARNING)
        }

        val ids = fixed.components.map { it.id.trim() }
        if (ids.any { it.isBlank() } || ids.distinct().size != ids.size ||
            fixed.components.any { !it.reliable || !it.amount.isFinite() || it.amount < 0.0 }
        ) return blocked(upstream + fixed.components.flatMap { it.warnings } + COMPONENT_WARNING)

        var extras = 0.0
        for (component in fixed.components) {
            extras += component.amount
            if (!extras.isFinite()) {
                return blocked(upstream + fixed.components.flatMap { it.warnings } + AMOUNT_WARNING)
            }
        }
        val cash = workedGross + extras
        if (!cash.isFinite() || cash < 0.0) {
            return blocked(upstream + fixed.components.flatMap { it.warnings } + AMOUNT_WARNING)
        }

        return SegmentedCashGrossAssemblyResultV2(
            workedGross = workedGross,
            additionalCashGross = extras,
            cashGross = cash,
            reliable = true,
            warnings = (upstream + fixed.components.flatMap { it.warnings }).distinct()
        )
    }

    private fun blocked(warnings: List<String>) = SegmentedCashGrossAssemblyResultV2(
        workedGross = null,
        additionalCashGross = null,
        cashGross = null,
        reliable = false,
        warnings = warnings.distinct()
    )
}
