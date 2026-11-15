package com.amaury.pointage.v2.engine

/** Known employer contributions, never an exhaustive employer cost or a second net calculation. */
object EmployerContributionProjectionV2 {
    enum class Family(val id: String) {
        STATUTORY("statutory"), COMPLEMENTARY_RETIREMENT("complementaryRetirement"),
        CONVENTION_PROVIDENT("conventionProvident"), STATUS("status"), AT_MP("atMp"),
        MOBILITY("mobility"), UNEMPLOYMENT_AGS("unemploymentAgs"), WORKFORCE("workforce"),
        HEALTH_FAMILY("healthFamily"), APPRENTICESHIP("apprenticeship")
    }
    enum class State { KNOWN, MISSING, NOT_APPLICABLE, INVALID }
    data class Context(
        val companyId: String?, val year: Int, val month: Int,
        val contributionGrossCents: Long?, val reliable: Boolean
    ) {
        val valid: Boolean get() = reliable && !companyId.isNullOrBlank() && year > 0 &&
            month in 1..12 && validCents(contributionGrossCents)
    }
    data class Line(val family: Family, val state: State, val amountCents: Long? = null, val source: String? = null)
    data class Reduction(val state: State, val amountCents: Long? = null, val source: String? = null)
    data class Result(
        val context: Context, val lines: List<Line>, val reductions: Reduction,
        val knownSubtotalBeforeReductionsCents: Long?, val knownSubtotalAfterReductionsCents: Long?,
        val monetaryReliable: Boolean, val warnings: List<String>
    ) {
        val coverageComplete: Boolean get() = false
        val knownSubtotalBeforeReductions: Double? get() = knownSubtotalBeforeReductionsCents?.let(PayrollMoneyV2::amount)
        val knownSubtotalAfterReductions: Double? get() = knownSubtotalAfterReductionsCents?.let(PayrollMoneyV2::amount)
        fun amount(family: Family): Double? = lines.singleOrNull { it.family == family }
            ?.takeIf { context.valid && it.state in setOf(State.KNOWN, State.NOT_APPLICABLE) }
            ?.amountCents?.let(PayrollMoneyV2::amount)
    }

    fun line(family: Family, amountCents: Long?, source: String?, available: Boolean, invalid: Boolean = false): Line = when {
        invalid -> Line(family, State.INVALID, null, source)
        !available -> Line(family, State.MISSING, null, source)
        !validCents(amountCents) -> Line(family, State.INVALID, null, source)
        source.isNullOrBlank() -> Line(family, State.INVALID)
        else -> Line(family, State.KNOWN, amountCents, source)
    }

    fun reduction(amount: Double?, source: String?): Reduction = when {
        amount == null -> Reduction(State.MISSING)
        amount < 0 || !amount.isFinite() || !validCents(PayrollMoneyV2.cents(amount)) -> Reduction(State.INVALID, null, source)
        source.isNullOrBlank() -> Reduction(State.INVALID)
        else -> Reduction(State.KNOWN, PayrollMoneyV2.cents(amount), source)
    }

    fun assemble(context: Context, lines: List<Line>, reductions: Reduction = Reduction(State.MISSING), additionalWarnings: List<String> = emptyList()): Result {
        val warnings = mutableListOf("Cotisations patronales connues : sous-total partiel ; la couverture complète reste à confirmer.")
        val normalized = Family.entries.map { family ->
            val matching = lines.filter { it.family == family }
            if (matching.size > 1) Line(family, State.INVALID)
            else matching.singleOrNull()?.let { line ->
                when (line.state) {
                    State.MISSING -> if (line.amountCents == null) line else line.copy(state = State.INVALID, amountCents = null)
                    State.INVALID -> line.copy(amountCents = null)
                    State.NOT_APPLICABLE -> if (line.amountCents == null && !line.source.isNullOrBlank()) line
                        else line.copy(state = State.INVALID, amountCents = null)
                    State.KNOWN -> if (validCents(line.amountCents) && !line.source.isNullOrBlank()) line
                        else line.copy(state = State.INVALID, amountCents = null)
                }
            } ?: Line(family, State.MISSING)
        }
        val reduction = when (reductions.state) {
            State.MISSING -> if (reductions.amountCents == null) reductions else reductions.copy(state = State.INVALID, amountCents = null)
            State.INVALID -> reductions.copy(amountCents = null)
            State.NOT_APPLICABLE -> if (reductions.amountCents == null && !reductions.source.isNullOrBlank()) reductions
                else reductions.copy(state = State.INVALID, amountCents = null)
            State.KNOWN -> if (validCents(reductions.amountCents) && !reductions.source.isNullOrBlank()) reductions
                else reductions.copy(state = State.INVALID, amountCents = null)
        }
        val reductionCents = if (reduction.state == State.NOT_APPLICABLE) 0L else reduction.amountCents
        val known = normalized.filter { it.state == State.KNOWN }
        val before = if (context.valid && normalized.none { it.state == State.INVALID } && known.isNotEmpty())
            PayrollMoneyV2.sum(known.map { it.amountCents!! }) else null
        val after = if (before != null && reduction.state in setOf(State.KNOWN, State.NOT_APPLICABLE) &&
            reductionCents != null && reductionCents <= before)
            PayrollMoneyV2.sum(listOf(before, -reductionCents)) else null
        warnings += additionalWarnings.filter { it.isNotBlank() }.distinct()
        if (!context.valid) warnings += "Cotisations patronales : entreprise, période ou brut social non fiables."
        if (normalized.any { it.state == State.INVALID } || (known.isNotEmpty() && context.valid && before == null))
            warnings += "Cotisations patronales : montant ou somme non représentable ; sous-total indisponible."
        if (normalized.any { it.state == State.MISSING }) warnings += "Certaines contributions patronales restent à confirmer."
        if (reduction.state == State.MISSING) warnings += "Réductions/exonérations du mois à confirmer, y compris leur absence éventuelle."
        if (reduction.state == State.INVALID) warnings += "Réductions/exonérations : montant invalide."
        if (before != null && reduction.amountCents != null && reduction.amountCents > before)
            warnings += "Réductions/exonérations : montant supérieur au sous-total connu ; aucun montant après réductions n'est calculé."
        return Result(context, normalized, reduction, before, after,
            before != null && reduction.state != State.INVALID && (reductionCents == null || reductionCents <= before), warnings)
    }

    private fun validCents(value: Long?): Boolean = value != null && value >= 0 && PayrollMoneyV2.amount(value) != null
}
