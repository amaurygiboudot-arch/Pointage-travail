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

data class SegmentedWorkedVariableGrossBreakdownV2(
    val employerId: String,
    val versionId: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val overtimeGross: Double,
    val complementaryGross: Double,
    val premiumGross: Double,
    val variableOvertimeMinutes: Int = 0,
    val complementaryMinutes: Int = 0
) {
    val variableGross: Double
        get() = overtimeGross + complementaryGross + premiumGross
}

data class SegmentedWorkedVariableGrossSourceResultV2(
    val pieces: List<SegmentedWorkedVariableGrossPieceV2>,
    val reliable: Boolean,
    val warnings: List<String>,
    val breakdowns: List<SegmentedWorkedVariableGrossBreakdownV2> = emptyList()
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
    const val MISSING_WEEKS_WARNING =
        "Variables segmentées : aucune preuve hebdomadaire n'est fournie pour une tranche ; variable inconnue, calcul bloqué."
    const val WEEK_COVERAGE_WARNING =
        "Variables segmentées : les semaines reçues ne couvrent pas exactement les dates de la tranche ; calcul bloqué."
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
        val breakdownByContract = linkedMapOf<ContractSegmentKey, VariableAmounts>()
        val warnings = mutableListOf<String>()
        warnings += timeline.warnings

        for (slice in timeline.slices) {
            val key = sliceKey(slice)
            val supplied = evidenceByKey[key]
                ?: return blocked(warnings + COVERAGE_WARNING)

            // Une liste vide ne prouve pas un zéro : conserver une semaine explicitement qualifiée.
            if (supplied.weeks.isEmpty()) {
                return blocked(warnings + supplied.warnings + MISSING_WEEKS_WARNING)
            }

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

            if (!hasExactWeekCoverage(
                    startEpochDay = slice.startEpochDay,
                    endEpochDay = slice.endEpochDay,
                    weeks = supplied.weeks
                )
            ) {
                return blocked(warnings + supplied.warnings + WEEK_COVERAGE_WARNING)
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

            if (!variable.totalGross.isFinite() || variable.totalGross < -CURRENCY_TOLERANCE) {
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
            val normalized = if (kotlin.math.abs(variable.totalGross) <= CURRENCY_TOLERANCE) 0.0 else variable.totalGross
            val next = variableByContract.getOrDefault(contractKey, 0.0) + normalized
            if (!next.isFinite() || next < -CURRENCY_TOLERANCE) {
                return blocked(warnings + AMOUNT_WARNING)
            }
            variableByContract[contractKey] =
                if (kotlin.math.abs(next) <= CURRENCY_TOLERANCE) 0.0 else next
            val previousBreakdown = breakdownByContract[contractKey] ?: VariableAmounts()
            val nextBreakdown = previousBreakdown + variable
            if (!nextBreakdown.valid()) return blocked(warnings + AMOUNT_WARNING)
            breakdownByContract[contractKey] = nextBreakdown
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
            variableByContract.keys.toSet() != expectedContractKeys.toSet() ||
            breakdownByContract.keys.toSet() != expectedContractKeys.toSet()
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

        val breakdowns = contracts.calculationSegments
            .sortedBy { it.startEpochDay }
            .map { segment ->
                val key = ContractSegmentKey(
                    segment.snapshot.versionId.trim(),
                    segment.startEpochDay,
                    segment.endEpochDay
                )
                val item = breakdownByContract[key]
                    ?: return blocked(warnings + COVERAGE_WARNING)
                SegmentedWorkedVariableGrossBreakdownV2(
                    employerId = employerId,
                    versionId = key.versionId,
                    startEpochDay = key.startEpochDay,
                    endEpochDay = key.endEpochDay,
                    overtimeGross = item.overtimeGross,
                    complementaryGross = item.complementaryGross,
                    premiumGross = item.premiumGross,
                    variableOvertimeMinutes = item.variableOvertimeMinutes.toInt(),
                    complementaryMinutes = item.complementaryMinutes.toInt()
                )
            }

        return SegmentedWorkedVariableGrossSourceResultV2(
            pieces = pieces,
            reliable = true,
            warnings = warnings.distinct(),
            breakdowns = breakdowns
        )
    }

    private fun fullTimeVariable(
        contractualWeeklyMinutes: Int?,
        rate: Double,
        weeks: List<PayrollWeekV2>,
        rules: PayrollRulesV2,
        warnings: MutableList<String>
    ): VariableAmounts? {
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

        val variableOvertimeMinutes = exactVariableOvertimeMinutes(overtime.variableTiers) ?: return null
        val premiums = premiumGross(weeks, rate, rules) ?: return null
        return VariableAmounts(
            overtimeGross = overtime.variableOvertimeGross,
            premiumGross = premiums,
            variableOvertimeMinutes = variableOvertimeMinutes
        ).takeIf { it.valid() }
    }

    private fun partTimeVariable(
        contractualWeeklyMinutes: Int?,
        rate: Double,
        weeks: List<PayrollWeekV2>,
        rules: PayrollRulesV2,
        warnings: MutableList<String>
    ): VariableAmounts? {
        val contractual = contractualWeeklyMinutes?.takeIf { it > 0 } ?: return null
        var complementaryMinutes = 0L
        for (week in weeks) {
            val complementary = PartTimeComplementaryHoursV2.calculateWeek(
                contractualMinutes = contractual,
                paidMinutes = week.paidMinutes,
                grossHourlyRate = rate
            )
            warnings += complementary.warnings
            if (complementary.complementaryMinutes < 0) return null
            if (complementary.complementaryMinutes > 0) return null
            complementaryMinutes += complementary.complementaryMinutes.toLong()
            if (complementaryMinutes > Int.MAX_VALUE) return null
        }
        val premiums = premiumGross(weeks, rate, rules) ?: return null
        return VariableAmounts(
            complementaryMinutes = complementaryMinutes,
            premiumGross = premiums
        ).takeIf { it.valid() }
    }

    private fun exactVariableOvertimeMinutes(
        tiers: List<FullTimeStructuralOvertimeV2.TierAmount>
    ): Long? {
        var total = 0L
        for (tier in tiers) {
            val minutes = tier.minutes
            if (!minutes.isFinite() || minutes < 0.0 || minutes > Int.MAX_VALUE.toDouble()) return null
            val integral = minutes.toLong()
            if (integral.toDouble() != minutes) return null
            total = try {
                Math.addExact(total, integral)
            } catch (_: ArithmeticException) {
                return null
            }
            if (total > Int.MAX_VALUE.toLong()) return null
        }
        return total
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

    /**
     * Vérifie les semaines ISO touchant les bornes inclusives, sans modifier les durées/montants.
     * Calendrier grégorien proleptique, années civiles 1 à 9999, commun Android/iOS.
     * Le nombre attendu est contrôlé avant la boucle : aucune plage corrompue n'est parcourue.
     * Une semaine de bord conserve son contexte complet ; aucune remise à zéro des seuils.
     */
    private fun hasExactWeekCoverage(
        startEpochDay: Long,
        endEpochDay: Long,
        weeks: List<SegmentedPayrollWeekEvidenceV2>
    ): Boolean {
        if (startEpochDay < -719162L || endEpochDay > 2932896L ||
            endEpochDay < startEpochDay
        ) return false

        val firstMonday = startEpochDay - ((startEpochDay % 7L + 10L) % 7L)
        val lastMonday = endEpochDay - ((endEpochDay % 7L + 10L) % 7L)
        val expectedCount = (lastMonday - firstMonday) / 7L + 1L
        if (weeks.size.toLong() != expectedCount) return false

        val seen = mutableSetOf<Long>()
        for (item in weeks) {
            if (item.weekYear !in 1..9999 || item.weekOfYear !in 1..53) return false
            val yearStart = firstIsoMonday(item.weekYear)
            val monday = yearStart + (item.weekOfYear - 1).toLong() * 7L
            if (monday >= firstIsoMonday(item.weekYear + 1) ||
                monday < firstMonday || monday > lastMonday || !seen.add(monday)
            ) return false
        }
        return true
    }

    // La semaine ISO 1 contient le 4 janvier. Nombre de jours grégoriens avant l'année :
    // 365*y + y/4 - y/100 + y/400. -719159 rattache le 4 janvier à l'epoch Unix.
    // Appel uniquement avec year dans 1..10000, après validation des identifiants.
    private fun firstIsoMonday(year: Int): Long {
        val y = year.toLong() - 1L
        val january4 = 365L * y + y / 4L - y / 100L + y / 400L - 719159L
        return january4 - ((january4 % 7L + 10L) % 7L)
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

    private data class VariableAmounts(
        val overtimeGross: Double = 0.0,
        val complementaryGross: Double = 0.0,
        val premiumGross: Double = 0.0,
        val variableOvertimeMinutes: Long = 0L,
        val complementaryMinutes: Long = 0L
    ) {
        val totalGross: Double get() = overtimeGross + complementaryGross + premiumGross
        operator fun plus(other: VariableAmounts) = VariableAmounts(
            overtimeGross + other.overtimeGross,
            complementaryGross + other.complementaryGross,
            premiumGross + other.premiumGross,
            variableOvertimeMinutes + other.variableOvertimeMinutes,
            complementaryMinutes + other.complementaryMinutes
        )
        fun valid(): Boolean =
            variableOvertimeMinutes in 0L..Int.MAX_VALUE.toLong() &&
                complementaryMinutes in 0L..Int.MAX_VALUE.toLong() &&
                listOf(
                    overtimeGross, complementaryGross, premiumGross, totalGross
                ).all { it.isFinite() && it >= -CURRENCY_TOLERANCE }
    }

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
