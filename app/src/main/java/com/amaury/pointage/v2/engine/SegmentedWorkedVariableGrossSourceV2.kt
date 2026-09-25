package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2

/**
 * Preuve hebdomadaire déjà construite pour une tranche contrat+règles.
 *
 * fullWeekContextReliable signifie que la semaine a été qualifiée avec son contexte hebdomadaire
 * complet : aucune borne de mois, contrat ou règle n'a remis artificiellement les compteurs à zéro.
 * Une même semaine ne doit apparaître qu'une fois, y compris au sein d'une même tranche.
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
    val paidTimeReliable: Boolean,
    val premiumTimeBreakdownReliable: Boolean,
    val payrollRulesReliable: Boolean,
    val warnings: List<String> = emptyList()
) {
    val grossInputsReliable: Boolean
        get() = paidTimeReliable && premiumTimeBreakdownReliable && payrollRulesReliable
}

data class SegmentedWorkedVariableGrossSourceResultV2(
    val pieces: List<SegmentedWorkedVariableGrossPieceV2>,
    val reliable: Boolean,
    val warnings: List<String>
)

/**
 * Produit les variables de brut par segment contractuel à partir de semaines déjà qualifiées.
 *
 * La base mensualisée est volontairement exclue : elle appartient à B17/B20.
 * Ici, seules les composantes réellement additionnelles sont produites :
 * - temps plein : heures supplémentaires variables + majorations temporelles ;
 * - temps partiel : majorations temporelles uniquement tant qu'aucune règle structurée fiable
 *   ne remplace le barème supplétif des heures complémentaires.
 *
 * Toute semaine dupliquée ou partagée entre tranches, toute preuve incomplète ou tout palier non couvert
 * laisse la variable inconnue. Aucun zéro n'est créé par défaut.
 */
object SegmentedWorkedVariableGrossSourceV2 {
    const val TIMELINE_WARNING =
        "Variables segmentées : la timeline contrat/règles est absente ou non fiable ; calcul bloqué."
    const val COVERAGE_WARNING =
        "Variables segmentées : les preuves hebdomadaires ne correspondent pas exactement aux tranches de calcul."
    const val WEEK_CONTEXT_WARNING =
        "Variables segmentées : une semaine est tronquée ou partagée entre plusieurs tranches ; les seuils hebdomadaires ne sont pas fiables."
    const val DUPLICATE_WEEK_WARNING =
        "Variables segmentées : une même semaine est fournie plusieurs fois dans une tranche ; calcul bloqué pour éviter un double comptage."
    const val EVIDENCE_WARNING =
        "Variables segmentées : les preuves de temps/règles/majorations sont incomplètes ; calcul bloqué."
    const val INVALID_PAID_TIME_WARNING =
        "Variables segmentées : une durée payée hebdomadaire est négative ; calcul bloqué."
    const val UNSUPPORTED_CONTRACT_WARNING =
        "Variables segmentées : ce type de contrat n'est pas supporté par la base segmentée actuelle."
    const val PART_TIME_COMPLEMENTARY_WARNING =
        "Variables segmentées : des heures complémentaires temps partiel existent mais leur barème conventionnel structuré n'est pas prouvé ; variable bloquée."
    const val OVERTIME_WARNING =
        "Variables segmentées : les heures supplémentaires variables ne sont pas entièrement couvertes par des paliers confirmés."
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

        val orderedSlices = timeline.slices.sortedBy { it.startEpochDay }
        for (index in 1 until orderedSlices.size) {
            val previous = orderedSlices[index - 1]
            val current = orderedSlices[index]
            val contractChanged = !payrollEquivalent(
                previous.contractSnapshot.contract,
                current.contractSnapshot.contract
            )
            val rulesChanged = previous.ruleSnapshot.rules != current.ruleSnapshot.rules
            if ((contractChanged || rulesChanged) && !isMondayEpochDay(current.startEpochDay)) {
                return blocked(timeline.warnings + WEEK_CONTEXT_WARNING)
            }
        }

        val expectedKeys = timeline.slices.map(::sliceKey)
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

        val weekOwners = linkedMapOf<Pair<Int, Int>, SliceKey>()
        val variableByContract = linkedMapOf<ContractSegmentKey, Double>()
        val warnings = mutableListOf<String>()
        warnings += timeline.warnings

        for (slice in timeline.slices) {
            val key = sliceKey(slice)
            val supplied = evidenceByKey[key]
                ?: return blocked(warnings + COVERAGE_WARNING)

            // Bloquer avant les préconditions des calculateurs, sans convertir la corruption en zéro.
            if (supplied.weeks.any { it.week.paidMinutes < 0 }) {
                return blocked(warnings + supplied.warnings + INVALID_PAID_TIME_WARNING)
            }

            if (!supplied.grossInputsReliable ||
                supplied.weeks.any { !it.fullWeekContextReliable }
            ) {
                return blocked(warnings + supplied.warnings + EVIDENCE_WARNING)
            }

            supplied.weeks.forEach { week ->
                val weekKey = week.weekYear to week.weekOfYear
                val previous = weekOwners.putIfAbsent(weekKey, key)
                if (previous != null) {
                    val warning = if (previous == key) {
                        DUPLICATE_WEEK_WARNING
                    } else {
                        WEEK_CONTEXT_WARNING
                    }
                    return blocked(warnings + warning)
                }
            }

            val contract = slice.contractSnapshot.contract
            val rate = contract.grossHourlyRate
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?: return blocked(warnings + AMOUNT_WARNING)
            val payrollRules = slice.ruleSnapshot.rules
            val payrollWeeks = supplied.weeks.map { it.week }

            val variable = when (contract.type) {
                ContractTypeV2.FULL_TIME -> fullTimeVariable(
                    contractualWeeklyMinutes = contract.contractualWeeklyMinutes,
                    rate = rate,
                    weeks = payrollWeeks,
                    rules = payrollRules,
                    warnings = warnings
                ) ?: return blocked(warnings + OVERTIME_WARNING)

                ContractTypeV2.PART_TIME -> partTimeVariable(
                    contractualWeeklyMinutes = contract.contractualWeeklyMinutes,
                    rate = rate,
                    weeks = payrollWeeks,
                    rules = payrollRules,
                    warnings = warnings
                ) ?: return blocked(warnings + PART_TIME_COMPLEMENTARY_WARNING)

                ContractTypeV2.FORFAIT_HOURS,
                ContractTypeV2.FORFAIT_DAYS,
                ContractTypeV2.FORFAIT,
                ContractTypeV2.OTHER -> return blocked(
                    warnings + UNSUPPORTED_CONTRACT_WARNING
                )
            }

            if (!variable.isFinite() || variable < -CURRENCY_TOLERANCE) {
                return blocked(warnings + AMOUNT_WARNING)
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
            val normalized = if (kotlin.math.abs(variable) <= CURRENCY_TOLERANCE) 0.0 else variable
            val next = variableByContract.getOrDefault(contractKey, 0.0) + normalized
            if (!next.isFinite() || next < -CURRENCY_TOLERANCE) {
                return blocked(warnings + AMOUNT_WARNING)
            }
            variableByContract[contractKey] =
                if (kotlin.math.abs(next) <= CURRENCY_TOLERANCE) 0.0 else next
            warnings += supplied.warnings
        }

        val expectedContractKeys = contracts.calculationSegments.map {
            ContractSegmentKey(
                it.snapshot.versionId.trim(),
                it.startEpochDay,
                it.endEpochDay
            )
        }
        if (expectedContractKeys.distinct().size != expectedContractKeys.size ||
            variableByContract.keys.toSet() != expectedContractKeys.toSet()
        ) {
            return blocked(warnings + COVERAGE_WARNING)
        }

        val employerId = contracts.employerId.trim()
        if (employerId.isBlank()) return blocked(warnings + COVERAGE_WARNING)

        val pieces = contracts.calculationSegments
            .sortedBy { it.startEpochDay }
            .map { segment ->
                val key = ContractSegmentKey(
                    segment.snapshot.versionId.trim(),
                    segment.startEpochDay,
                    segment.endEpochDay
                )
                SegmentedWorkedVariableGrossPieceV2(
                    employerId = employerId,
                    versionId = key.versionId,
                    startEpochDay = key.startEpochDay,
                    endEpochDay = key.endEpochDay,
                    variableGross = variableByContract[key]
                        ?: return blocked(warnings + COVERAGE_WARNING),
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

    private fun fullTimeVariable(
        contractualWeeklyMinutes: Int?,
        rate: Double,
        weeks: List<PayrollWeekV2>,
        rules: PayrollRulesV2,
        warnings: MutableList<String>
    ): Double? {
        val contractual = contractualWeeklyMinutes?.takeIf { it > 0 } ?: return null
        val regularLimit = rules.weeklyRegularMinutes?.takeIf { it > 0 } ?: return null

        val overtime = FullTimeStructuralOvertimeV2.calculate(
            contractualWeeklyMinutes = contractual,
            regularWeeklyLimit = regularLimit,
            paidWeeks = weeks.map { it.paidMinutes },
            grossHourlyRate = rate,
            overtimeTiers = rules.overtimeTiers
        )
        warnings += overtime.warnings
        if (overtime.provisionalRateUsed ||
            overtime.unresolvedStructuralOvertimeMinutes > 0.0 ||
            overtime.unresolvedVariableOvertimeMinutes > 0.0
        ) {
            return null
        }

        val premiums = premiumGross(weeks, rate, rules) ?: return null
        val total = overtime.variableOvertimeGross + premiums
        return total.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun partTimeVariable(
        contractualWeeklyMinutes: Int?,
        rate: Double,
        weeks: List<PayrollWeekV2>,
        rules: PayrollRulesV2,
        warnings: MutableList<String>
    ): Double? {
        val contractual = contractualWeeklyMinutes?.takeIf { it > 0 } ?: return null
        for (week in weeks) {
            val complementary = PartTimeComplementaryHoursV2.calculateWeek(
                contractualMinutes = contractual,
                paidMinutes = week.paidMinutes,
                grossHourlyRate = rate
            )
            warnings += complementary.warnings
            if (complementary.complementaryMinutes > 0) return null
        }
        return premiumGross(weeks, rate, rules)
    }

    private fun premiumGross(
        weeks: List<PayrollWeekV2>,
        rate: Double,
        rules: PayrollRulesV2
    ): Double? {
        return try {
            weeks.sumOf {
                PayrollPremiumGrossV2.calculate(
                    week = it,
                    grossHourlyRate = rate,
                    rules = rules
                )
            }.takeIf { it.isFinite() && it >= 0.0 }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun payrollEquivalent(
        a: com.amaury.pointage.v2.model.ContractV2,
        b: com.amaury.pointage.v2.model.ContractV2
    ): Boolean =
        a.employerId.trim() == b.employerId.trim() &&
            a.type == b.type &&
            a.contractualWeeklyMinutes == b.contractualWeeklyMinutes &&
            a.grossHourlyRate == b.grossHourlyRate &&
            a.hireDateEpochDay == b.hireDateEpochDay &&
            a.payrollCutoffDay == b.payrollCutoffDay &&
            a.forfaitHoursPeriod == b.forfaitHoursPeriod &&
            a.forfaitHours == b.forfaitHours &&
            a.forfaitAnnualDays == b.forfaitAnnualDays &&
            a.monthlyGrossSalary == b.monthlyGrossSalary

    private fun isMondayEpochDay(epochDay: Long): Boolean =
        ((epochDay % 7L) + 7L) % 7L == 4L

    private fun sliceKey(slice: PayrollCalculationSliceV2) = SliceKey(
        slice.startEpochDay,
        slice.endEpochDay,
        slice.contractVersionId.trim(),
        slice.ruleVersionId.trim()
    )

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
