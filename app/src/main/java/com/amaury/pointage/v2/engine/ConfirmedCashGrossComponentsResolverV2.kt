package com.amaury.pointage.v2.engine

data class ConfirmedCashGrossFixedFactV2(
    val id: String,
    val amount: Double?,
    val applicable: Boolean,
    val reliable: Boolean,
    val warnings: List<String> = emptyList()
)

/**
 * Transforme uniquement des faits fixes déjà résolus en paquet consommable par le cash gross.
 * Aucune lecture de store ni formule métier ici.
 */
object ConfirmedCashGrossComponentsResolverV2 {
    const val COVERAGE_WARNING =
        "Brut en espèces segmenté : exhaustivité des composantes fixes non confirmée."
    const val FACT_WARNING =
        "Brut en espèces segmenté : composante fixe applicable absente, dupliquée ou non fiable."

    fun resolve(
        facts: List<ConfirmedCashGrossFixedFactV2>,
        exhaustive: Boolean,
        sourceId: String,
        warnings: List<String> = emptyList()
    ): ConfirmedCashGrossComponentsV2 {
        val ids = facts.map { it.id.trim() }
        val uniqueIds = ids.none { it.isBlank() } && ids.distinct().size == ids.size
        val factsReliable = facts.all { fact ->
            fact.reliable &&
                (!fact.applicable || fact.amount?.let { it.isFinite() && it >= 0.0 } == true)
        }
        val safeComponents = facts.mapNotNull { fact ->
            val amount = fact.amount
            if (!fact.applicable || !fact.reliable || amount == null || !amount.isFinite() || amount < 0.0) {
                null
            } else {
                ConfirmedCashGrossComponentV2(
                    id = fact.id.trim(),
                    amount = amount,
                    reliable = true,
                    warnings = fact.warnings
                )
            }
        }
        val resolvedExhaustive = exhaustive && sourceId.isNotBlank() && uniqueIds && factsReliable
        val allWarnings = buildList {
            addAll(warnings)
            addAll(facts.flatMap { it.warnings })
            if (!exhaustive || sourceId.isBlank()) add(COVERAGE_WARNING)
            if (!uniqueIds || !factsReliable) add(FACT_WARNING)
        }.distinct()
        return ConfirmedCashGrossComponentsV2(
            components = safeComponents,
            exhaustive = resolvedExhaustive,
            sourceId = sourceId.trim(),
            warnings = allWarnings
        )
    }
}
