package com.amaury.pointage.v2.engine

/**
 * Part variable de brut rattachée à un segment contractuel déjà prouvé.
 *
 * Cette structure ne calcule aucune heure et ne choisit aucune règle. Elle reçoit uniquement
 * un montant amont déjà qualifié comme fiable pour les mêmes bornes/version que la base
 * mensuelle segmentée. Un zéro fiable est distinct d'une pièce absente ou non fiable.
 */
data class SegmentedWorkedVariableGrossPieceV2(
    val employerId: String,
    val versionId: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val variableGross: Double,
    val reliable: Boolean,
    val warnings: List<String> = emptyList()
)

data class SegmentedWorkedGrossAssemblyResultV2(
    val baseGross: Double?,
    val variableGross: Double?,
    val workedGross: Double?,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Assemble uniquement :
 *   base mensualisée proratisée confirmée + variables de travail prouvées.
 *
 * Ne sont volontairement PAS ajoutés ici : primes fixes mensuelles non rattachées au temps,
 * avantages en nature, paniers, retenues, cotisations, PAS ou net. Cette couche est donc un
 * intermédiaire de brut de travail et ne doit pas être présentée seule comme brut social final.
 *
 * Le moteur est fail-closed :
 * - base absente/non fiable => blocage ;
 * - pièce variable manquante => inconnu, jamais zéro implicite ;
 * - doublon ou bornes/version différentes => blocage ;
 * - pièce non fiable => blocage ;
 * - NaN/infini/négatif => blocage.
 */
object SegmentedWorkedGrossAssemblerV2 {
    const val CONTRACT_WARNING =
        "Brut segmenté : la couverture contractuelle sélectionnée est absente, non fiable ou ne correspond pas aux segments monétaires."
    const val BASE_WARNING =
        "Brut segmenté : base mensuelle segmentée absente, incohérente ou non fiable ; assemblage bloqué."
    const val COVERAGE_WARNING =
        "Brut segmenté : les variables prouvées ne correspondent pas exactement aux segments de base ; aucun zéro implicite n'est ajouté."
    const val VARIABLE_RELIABILITY_WARNING =
        "Brut segmenté : au moins une variable de travail du segment reste non fiable ; brut de travail bloqué."
    const val AMOUNT_WARNING =
        "Brut segmenté : montant non fini ou négatif détecté ; assemblage bloqué."
    const val OVERFLOW_WARNING =
        "Brut segmenté : total monétaire non représentable de façon fiable ; assemblage bloqué."
    const val BREAKDOWN_WARNING =
        "Brut segment? : la ventilation des variables ne correspond pas aux pi?ces mon?taires prouv?es ; raccord aval bloqu?."

    fun assemble(
        contracts: EmploymentContractPeriodResolutionV2,
        base: SegmentedMonthlyBaseResultV2,
        variableSource: SegmentedWorkedVariableGrossSourceResultV2
    ): SegmentedWorkedGrossAssemblyResultV2 {
        if (!variableSource.reliable) {
            return blocked(
                contracts.warnings +
                    base.warnings +
                    variableSource.warnings +
                    VARIABLE_RELIABILITY_WARNING
            )
        }
        if (variableSource.breakdowns.isNotEmpty()) {
            val pieceByKey = variableSource.pieces.associateBy {
                key(it.versionId, it.startEpochDay, it.endEpochDay)
            }
            val breakdownKeys = variableSource.breakdowns.map {
                key(it.versionId, it.startEpochDay, it.endEpochDay)
            }
            val valid = pieceByKey.size == variableSource.pieces.size &&
                breakdownKeys.distinct().size == breakdownKeys.size &&
                breakdownKeys.toSet() == pieceByKey.keys &&
                variableSource.breakdowns.all { item ->
                    val amounts = listOf(
                        item.overtimeGross,
                        item.complementaryGross,
                        item.premiumGross,
                        item.variableGross
                    )
                    val piece = pieceByKey[key(item.versionId, item.startEpochDay, item.endEpochDay)]
                    item.employerId.trim() == contracts.employerId.trim() &&
                        amounts.all { it.isFinite() && it >= 0.0 } &&
                        piece != null &&
                        kotlin.math.abs(item.variableGross - piece.variableGross) <= CURRENCY_TOLERANCE
                }
            if (!valid) {
                return blocked(
                    contracts.warnings + base.warnings + variableSource.warnings + BREAKDOWN_WARNING
                )
            }
        }
        val assembled = assemble(
            contracts = contracts,
            base = base,
            variables = variableSource.pieces
        )
        return assembled.copy(
            warnings = (assembled.warnings + variableSource.warnings).distinct()
        )
    }

    fun assemble(
        contracts: EmploymentContractPeriodResolutionV2,
        base: SegmentedMonthlyBaseResultV2,
        variables: List<SegmentedWorkedVariableGrossPieceV2>
    ): SegmentedWorkedGrossAssemblyResultV2 {
        val employerId = contracts.employerId.trim()
        val contractSegments = contracts.calculationSegments
        if (!contracts.sourceReliable ||
            employerId.isBlank() ||
            contractSegments.isEmpty() ||
            contractSegments.any { it.snapshot.contract.employerId.trim() != employerId }
        ) {
            return blocked(contracts.warnings + base.warnings + CONTRACT_WARNING)
        }
        val upstreamWarnings = contracts.warnings + base.warnings
        val baseAmount = base.baseGross
        if (!base.reliable ||
            baseAmount == null ||
            !baseAmount.isFinite() ||
            baseAmount < 0.0 ||
            base.pieces.isEmpty()
        ) {
            return blocked(upstreamWarnings + BASE_WARNING)
        }

        val baseKeys = base.pieces.map {
            key(it.versionId, it.startEpochDay, it.endEpochDay)
        }
        val contractKeys = contractSegments.map {
            key(it.snapshot.versionId, it.startEpochDay, it.endEpochDay)
        }
        if (baseKeys.any { it.first.isBlank() } ||
            baseKeys.distinct().size != baseKeys.size ||
            base.pieces.any { it.endEpochDay < it.startEpochDay }
        ) {
            return blocked(upstreamWarnings + BASE_WARNING)
        }
        if (contractKeys.any { it.first.isBlank() } ||
            contractKeys.distinct().size != contractKeys.size ||
            contractKeys.toSet() != baseKeys.toSet()
        ) {
            return blocked(upstreamWarnings + CONTRACT_WARNING)
        }

        var recomputedBase = 0.0
        var factorTotal = 0.0
        var scheduledTotal = 0L
        for (piece in base.pieces) {
            if (piece.scheduledMinutes < 0 ||
                !piece.factor.isFinite() ||
                piece.factor < 0.0 ||
                piece.factor > 1.0 + FACTOR_TOLERANCE ||
                !piece.fullMonthBaseGross.isFinite() ||
                piece.fullMonthBaseGross < 0.0 ||
                !piece.proratedBaseGross.isFinite() ||
                piece.proratedBaseGross < 0.0
            ) {
                return blocked(upstreamWarnings + AMOUNT_WARNING)
            }

            val expectedPiece = piece.fullMonthBaseGross * piece.factor
            if (!expectedPiece.isFinite() ||
                kotlin.math.abs(expectedPiece - piece.proratedBaseGross) > CURRENCY_TOLERANCE
            ) {
                return blocked(upstreamWarnings + BASE_WARNING)
            }

            recomputedBase += piece.proratedBaseGross
            factorTotal += piece.factor
            val scheduledValue = piece.scheduledMinutes.toLong()
            if (scheduledTotal > Long.MAX_VALUE - scheduledValue) {
                return blocked(upstreamWarnings + OVERFLOW_WARNING)
            }
            scheduledTotal += scheduledValue

            if (!recomputedBase.isFinite() || !factorTotal.isFinite()) {
                return blocked(upstreamWarnings + OVERFLOW_WARNING)
            }
        }
        if (scheduledTotal <= 0L ||
            kotlin.math.abs(factorTotal - 1.0) > FACTOR_TOLERANCE
        ) {
            return blocked(upstreamWarnings + BASE_WARNING)
        }
        for (piece in base.pieces) {
            val expectedFactor = piece.scheduledMinutes.toDouble() / scheduledTotal.toDouble()
            if (!expectedFactor.isFinite() ||
                kotlin.math.abs(expectedFactor - piece.factor) > FACTOR_TOLERANCE
            ) {
                return blocked(upstreamWarnings + BASE_WARNING)
            }
        }
        if (kotlin.math.abs(recomputedBase - baseAmount) > CURRENCY_TOLERANCE) {
            return blocked(upstreamWarnings + BASE_WARNING)
        }

        val variableKeys = variables.map {
            key(it.versionId, it.startEpochDay, it.endEpochDay)
        }
        if (variableKeys.any { it.first.isBlank() } ||
            variableKeys.distinct().size != variableKeys.size ||
            variables.any {
                it.employerId.trim() != employerId ||
                    it.endEpochDay < it.startEpochDay
            } ||
            variableKeys.toSet() != baseKeys.toSet()
        ) {
            return blocked(upstreamWarnings + variables.flatMap { it.warnings } + COVERAGE_WARNING)
        }

        if (variables.any { !it.reliable }) {
            return blocked(upstreamWarnings + variables.flatMap { it.warnings } + VARIABLE_RELIABILITY_WARNING)
        }

        var variableTotal = 0.0
        for (piece in variables) {
            if (!piece.variableGross.isFinite() || piece.variableGross < 0.0) {
                return blocked(upstreamWarnings + variables.flatMap { it.warnings } + AMOUNT_WARNING)
            }
            variableTotal += piece.variableGross
            if (!variableTotal.isFinite()) {
                return blocked(upstreamWarnings + variables.flatMap { it.warnings } + OVERFLOW_WARNING)
            }
        }

        val workedGross = baseAmount + variableTotal
        if (!workedGross.isFinite()) {
            return blocked(upstreamWarnings + variables.flatMap { it.warnings } + OVERFLOW_WARNING)
        }

        return SegmentedWorkedGrossAssemblyResultV2(
            baseGross = baseAmount,
            variableGross = variableTotal,
            workedGross = workedGross,
            reliable = true,
            warnings = (upstreamWarnings + variables.flatMap { it.warnings }).distinct()
        )
    }

    private fun key(
        versionId: String,
        startEpochDay: Long,
        endEpochDay: Long
    ): Triple<String, Long, Long> =
        Triple(versionId.trim(), startEpochDay, endEpochDay)

    private fun blocked(warnings: List<String>) =
        SegmentedWorkedGrossAssemblyResultV2(
            baseGross = null,
            variableGross = null,
            workedGross = null,
            reliable = false,
            warnings = warnings.distinct()
        )

    private const val CURRENCY_TOLERANCE = 0.005
    private const val FACTOR_TOLERANCE = 0.000_000_001
}
