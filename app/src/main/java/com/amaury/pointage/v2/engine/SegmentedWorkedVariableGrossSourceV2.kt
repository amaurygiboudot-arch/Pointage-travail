package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2

/**
 * Preuve hebdomadaire déjà construite pour une tranche contrat+règles.
 *
 * fullWeekContextReliable signifie que la semaine a été qualifiée avec son contexte hebdomadaire
 * complet : aucune borne de mois, contrat ou règle n'a remis artificiellement les compteurs à zéro.
 * Une même semaine ne doit jamais apparaître dans deux tranches différentes.
 */
data class SegmentedPayrollWeekEvidenceV2(
    val weekYear: Int,
    val weekOfYear: Int,
    val week: PayrollWeekV2,
    val fullWeekContextReliable: Boolean
)

data class SegmentedPayrollSliceEvidenceV2(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val contractVersionId: String,
    val ruleVersionId: String,
    val weeks: List<SegmentedPayrollWeekEvidenceV2>,
    val evidence: PayrollInputEvidenceV2
)

data class SegmentedWorkedVariableGrossSourceResultV2(
    val pieces: List<SegmentedWorkedVariableGrossPieceV2>,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Produit les variables de brut par segment contractuel à partir de semaines déjà qualifiées.
 *
 * Cette couche ne lit aucun écran et ne reconstruit aucun pointage. Elle consomme uniquement :
 * - la timeline canonique contrat + règles ;
 * - des semaines complètes avec ventilation premium prouvée ;
 * - PayrollEngineV2, propriétaire unique du calcul brut.
 *
 * Pour un temps plein, la base mensuelle déjà couverte par B17 est retirée du résultat
 * PayrollEngineV2 afin de ne conserver que les variables réellement additionnelles :
 * heures supplémentaires variables + majorations temporelles.
 *
 * Pour un temps partiel, la base mensualisée est également retirée. Si des heures
 * complémentaires utilisent encore un barème provisoire, PayrollEngineV2.grossReliable
 * reste faux et la source bloque au lieu de promouvoir ce montant.
 */
object SegmentedWorkedVariableGrossSourceV2 {
    const val TIMELINE_WARNING =
        "Variables segmentées : la timeline contrat/règles est absente ou non fiable ; calcul bloqué."
    const val COVERAGE_WARNING =
        "Variables segmentées : les preuves hebdomadaires ne correspondent pas exactement aux tranches de calcul."
    const val WEEK_CONTEXT_WARNING =
        "Variables segmentées : une semaine est tronquée ou partagée entre plusieurs tranches ; les seuils hebdomadaires ne sont pas fiables."
    const val EVIDENCE_WARNING =
        "Variables segmentées : les preuves de temps/règles/majorations sont incomplètes ; calcul bloqué."
    const val UNSUPPORTED_CONTRACT_WARNING =
        "Variables segmentées : ce type de contrat n'est pas supporté par la base segmentée actuelle."
    const val PAYROLL_WARNING =
        "Variables segmentées : PayrollEngineV2 ne peut pas produire un brut variable fiable pour cette tranche."
    const val AMOUNT_WARNING =
        "Variables segmentées : montant variable non fini ou négatif ; calcul bloqué."

    fun calculate(
        contracts: EmploymentContractPeriodResolutionV2,
        rules: ConventionRulePeriodResolutionV2,
        sliceEvidence: List<SegmentedPayrollSliceEvidenceV2>
    ): SegmentedWorkedVariableGrossSourceResultV2 {
        val timeline = PayrollCalculationTimelineV2.align(contracts, rules)
        if (!timeline.reliable || timeline.slices.isEmpty()) {
            return blocked(timeline.warnings + TIMELINE_WARNING)
        }

        val expectedKeys = timeline.slices.map {
            SliceKey(
                it.startEpochDay,
                it.endEpochDay,
                it.contractVersionId.trim(),
                it.ruleVersionId.trim()
            )
        }
        val providedKeys = sliceEvidence.map {
            SliceKey(
                it.startEpochDay,
                it.endEpochDay,
                it.contractVersionId.trim(),
                it.ruleVersionId.trim()
            )
        }

        if (expectedKeys.any { it.contractVersionId.isBlank() || it.ruleVersionId.isBlank() } ||
            providedKeys.any { it.contractVersionId.isBlank() || it.ruleVersionId.isBlank() } ||
            expectedKeys.distinct().size != expectedKeys.size ||
            providedKeys.distinct().size != providedKeys.size ||
            expectedKeys.toSet() != providedKeys.toSet()
        ) {
            return blocked(timeline.warnings + COVERAGE_WARNING)
        }

        val evidenceByKey = sliceEvidence.associateBy {
            SliceKey(
                it.startEpochDay,
                it.endEpochDay,
                it.contractVersionId.trim(),
                it.ruleVersionId.trim()
            )
        }

        val allWeekOwners = linkedMapOf<Pair<Int, Int>, SliceKey>()
        val variableByContractKey = linkedMapOf<ContractSegmentKey, Double>()
        val warnings = mutableListOf<String>()
        warnings += timeline.warnings

        for (slice in timeline.slices) {
            val key = SliceKey(
                slice.startEpochDay,
                slice.endEpochDay,
                slice.contractVersionId.trim(),
                slice.ruleVersionId.trim()
            )
            val supplied = evidenceByKey[key]
                ?: return blocked(warnings + COVERAGE_WARNING)

            if (!supplied.evidence.grossInputsReliable ||
                supplied.weeks.any { !it.fullWeekContextReliable }
            ) {
                return blocked(warnings + supplied.evidence.warnings + EVIDENCE_WARNING)
            }

            for (week in supplied.weeks) {
                val weekKey = week.weekYear to week.weekOfYear
                val previousOwner = allWeekOwners.putIfAbsent(weekKey, key)
                if (previousOwner != null && previousOwner != key) {
                    return blocked(warnings + WEEK_CONTEXT_WARNING)
                }
            }

            val contract = slice.contractSnapshot.contract
            if (contract.type !in setOf(ContractTypeV2.FULL_TIME, ContractTypeV2.PART_TIME)) {
                return blocked(warnings + UNSUPPORTED_CONTRACT_WARNING)
            }

            val payroll = try {
                PayrollEngineV2.calculate(
                    contract = contract,
                    weeks = supplied.weeks.map { it.week },
                    rules = slice.ruleSnapshot.rules,
                    evidence = supplied.evidence
                )
            } catch (_: PayrollEngineErrorV2) {
                return blocked(warnings + PAYROLL_WARNING)
            }

            if (!payroll.grossReliable) {
                return blocked(warnings + payroll.traces + PAYROLL_WARNING)
            }

            val base = canonicalFullMonthBase(
                contractType = contract.type,
                regularGross = payroll.regularGross,
                contractWeeklyMinutes = contract.contractualWeeklyMinutes,
                regularLimit = slice.ruleSnapshot.rules.weeklyRegularMinutes,
                rate = contract.grossHourlyRate,
                overtimeTiers = slice.ruleSnapshot.rules.overtimeTiers
            ) ?: return blocked(warnings + PAYROLL_WARNING)

            val variableGross = payroll.grossEstimate - base
            if (!variableGross.isFinite() || variableGross < -CURRENCY_TOLERANCE) {
                return blocked(warnings + payroll.traces + AMOUNT_WARNING)
            }

            val contractSegment = contracts.calculationSegments.singleOrNull {
                it.snapshot.versionId.trim() == slice.contractVersionId.trim() &&
                    slice.startEpochDay >= it.startEpochDay &&
                    slice.endEpochDay <= it.endEpochDay
            } ?: return blocked(warnings + COVERAGE_WARNING)

            val contractKey = ContractSegmentKey(
                contractSegment.snapshot.versionId.trim(),
                contractSegment.startEpochDay,
                contractSegment.endEpochDay
            )
            val normalizedVariable =
                if (kotlin.math.abs(variableGross) <= CURRENCY_TOLERANCE) 0.0 else variableGross
            val next = variableByContractKey.getOrDefault(contractKey, 0.0) + normalizedVariable
            if (!next.isFinite() || next < -CURRENCY_TOLERANCE) {
                return blocked(warnings + AMOUNT_WARNING)
            }
            variableByContractKey[contractKey] =
                if (kotlin.math.abs(next) <= CURRENCY_TOLERANCE) 0.0 else next
            warnings += payroll.traces
        }

        val expectedContractKeys = contracts.calculationSegments.map {
            ContractSegmentKey(
                it.snapshot.versionId.trim(),
                it.startEpochDay,
                it.endEpochDay
            )
        }
        if (expectedContractKeys.distinct().size != expectedContractKeys.size ||
            variableByContractKey.keys.toSet() != expectedContractKeys.toSet()
        ) {
            return blocked(warnings + COVERAGE_WARNING)
        }

        val employerId = contracts.employerId.trim()
        if (employerId.isBlank()) return blocked(warnings + COVERAGE_WARNING)

        val pieces = mutableListOf<SegmentedWorkedVariableGrossPieceV2>()
        for (segment in contracts.calculationSegments.sortedBy { it.startEpochDay }) {
            val contractKey = ContractSegmentKey(
                segment.snapshot.versionId.trim(),
                segment.startEpochDay,
                segment.endEpochDay
            )
            val amount = variableByContractKey[contractKey]
                ?: return blocked(warnings + COVERAGE_WARNING)
            pieces += SegmentedWorkedVariableGrossPieceV2(
                employerId = employerId,
                versionId = contractKey.versionId,
                startEpochDay = contractKey.startEpochDay,
                endEpochDay = contractKey.endEpochDay,
                variableGross = amount,
                reliable = true,
                warnings = emptyList()
            )
        }

        return SegmentedWorkedVariableGrossSourceResultV2(
            pieces = pieces,
            reliable = true,
            warnings = warnings.distinct()
        )
    }

    private fun canonicalFullMonthBase(
        contractType: ContractTypeV2,
        regularGross: Double,
        contractWeeklyMinutes: Int?,
        regularLimit: Int?,
        rate: Double?,
        overtimeTiers: List<OvertimeTierV2>
    ): Double? {
        if (!regularGross.isFinite() || regularGross < 0.0) return null
        return when (contractType) {
            ContractTypeV2.PART_TIME -> regularGross

            ContractTypeV2.FULL_TIME -> {
                val weekly = contractWeeklyMinutes?.takeIf { it > 0 } ?: return null
                val limit = regularLimit?.takeIf { it > 0 } ?: return null
                val hourlyRate = rate?.takeIf { it.isFinite() && it > 0.0 } ?: return null
                val structural = FullTimeStructuralOvertimeV2.calculate(
                    contractualWeeklyMinutes = weekly,
                    regularWeeklyLimit = limit,
                    paidWeeks = emptyList(),
                    grossHourlyRate = hourlyRate,
                    overtimeTiers = overtimeTiers
                )
                if (structural.provisionalRateUsed ||
                    structural.unresolvedStructuralOvertimeMinutes > 0.0 ||
                    !structural.monthlyBaseGross.isFinite() ||
                    structural.monthlyBaseGross < 0.0
                ) {
                    null
                } else {
                    structural.monthlyBaseGross
                }
            }

            else -> null
        }
    }

    private fun blocked(warnings: List<String>) =
        SegmentedWorkedVariableGrossSourceResultV2(
            pieces = emptyList(),
            reliable = false,
            warnings = warnings.distinct()
        )

    private data class SliceKey(
        val startEpochDay: Long,
        val endEpochDay: Long,
        val contractVersionId: String,
        val ruleVersionId: String
    )

    private data class ContractSegmentKey(
        val versionId: String,
        val startEpochDay: Long,
        val endEpochDay: Long
    )

    private const val CURRENCY_TOLERANCE = 0.005
}
