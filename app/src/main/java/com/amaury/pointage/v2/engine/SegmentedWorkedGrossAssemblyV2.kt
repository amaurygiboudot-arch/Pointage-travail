package com.amaury.pointage.v2.engine

/**
 * Part variable de brut rattachée à un segment contractuel déjà prouvé.
 *
 * Cette structure ne calcule aucune heure et ne choisit aucune règle. Elle reçoit uniquement
 * un montant amont déjà qualifié comme fiable pour les mêmes bornes/version que la base
 * mensuelle segmentée. Un zéro fiable est distinct d'une pièce absente ou non fiable.
 */
data class SegmentedWorkedVariableGrossPieceV2(
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

    fun assemble(
        base: SegmentedMonthlyBaseResultV2,
        variables: List<SegmentedWorkedVariableGrossPieceV2>
    ): SegmentedWorkedGrossAssemblyResultV2 {
        val baseAmount = base.baseGross
        if (!base.reliable ||
            baseAmount == null ||
            !baseAmount.isFinite() ||
            baseAmount < 0.0 ||
            base.pieces.isEmpty()
        ) {
            return blocked(base.warnings + BASE_WARNING)
        }

        val baseKeys = base.pieces.map {
            key(it.versionId, it.startEpochDay, it.endEpochDay)
        }
        if (baseKeys.any { it.first.isBlank() } ||
            baseKeys.distinct().size != baseKeys.size ||
            base.pieces.any { it.endEpochDay < it.startEpochDay }
        ) {
            return blocked(base.warnings + BASE_WARNING)
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
                return blocked(base.warnings + AMOUNT_WARNING)
            }

            val expectedPiece = piece.fullMonthBaseGross * piece.factor
            if (!expectedPiece.isFinite() ||
                kotlin.math.abs(expectedPiece - piece.proratedBaseGross) > CURRENCY_TOLERANCE
            ) {
                return blocked(base.warnings + BASE_WARNING)
            }

            recomputedBase += piece.proratedBaseGross
            factorTotal += piece.factor
            val scheduledAddition = scheduledTotal.addingReportingOverflow(piece.scheduledMinutes.toLong())
            if (scheduledAddition.overflow) {
                return blocked(base.warnings + OVERFLOW_WARNING)
            }
            scheduledTotal = scheduledAddition.value

            if (!recomputedBase.isFinite() || !factorTotal.isFinite()) {
                return blocked(base.warnings + OVERFLOW_WARNING)
            }
        }
        if (scheduledTotal <= 0L ||
            kotlin.math.abs(factorTotal - 1.0) > FACTOR_TOLERANCE
        ) {
            return blocked(base.warnings + BASE_WARNING)
        }
        if (kotlin.math.abs(recomputedBase - baseAmount) > CURRENCY_TOLERANCE) {
            return blocked(base.warnings + BASE_WARNING)
        }

        val variableKeys = variables.map {
            key(it.versionId, it.startEpochDay, it.endEpochDay)
        }
        if (variableKeys.any { it.first.isBlank() } ||
            variableKeys.distinct().size != variableKeys.size ||
            variables.any { it.endEpochDay < it.startEpochDay } ||
            variableKeys.toSet() != baseKeys.toSet()
        ) {
            return blocked(base.warnings + variables.flatMap { it.warnings } + COVERAGE_WARNING)
        }

        if (variables.any { !it.reliable }) {
            return blocked(base.warnings + variables.flatMap { it.warnings } + VARIABLE_RELIABILITY_WARNING)
        }

        var variableTotal = 0.0
        for (piece in variables) {
            if (!piece.variableGross.isFinite() || piece.variableGross < 0.0) {
                return blocked(base.warnings + variables.flatMap { it.warnings } + AMOUNT_WARNING)
            }
            variableTotal += piece.variableGross
            if (!variableTotal.isFinite()) {
                return blocked(base.warnings + variables.flatMap { it.warnings } + OVERFLOW_WARNING)
            }
        }

        val workedGross = baseAmount + variableTotal
        if (!workedGross.isFinite()) {
            return blocked(base.warnings + variables.flatMap { it.warnings } + OVERFLOW_WARNING)
        }

        return SegmentedWorkedGrossAssemblyResultV2(
            baseGross = baseAmount,
            variableGross = variableTotal,
            workedGross = workedGross,
            reliable = true,
            warnings = (base.warnings + variables.flatMap { it.warnings }).distinct()
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
