package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * Décompte mensuel des paniers après arbitrage juridique ACCO/KALI.
 *
 * Les faits inconnus restent inconnus. Les pauses enregistrées sont retirées des intervalles de
 * travail effectif ; une pause ouverte/incohérente empêche de certifier le total du mois.
 * Les valeurs externes (ex. montant renvoyé à un autre accord) sont isolées par objet de panier.
 */
object VerifiedMealBasketPayrollV2 {
    data class FactDefaults(
        val postedShiftWorker: Boolean? = null,
        val canReturnHomeForMeal: Boolean? = null,
        val worksAwayFromUsualWorkplace: Boolean? = null,
        val mustEatAtWorkplace: Boolean? = null,
        val companyCanteenAvailable: Boolean? = null,
        val employerMealProvided: Boolean? = null,
        val mealVoucherProvided: Boolean? = null,
        val otherSameNatureMealBenefit: Boolean? = null,
        val employerNightWindow: ConventionMealBasketV2.DailyWindow? = null
    )

    data class Result(
        val count: Int,
        val totalAmount: Double?,
        val reliable: Boolean,
        val warnings: List<String>,
        val selectedSources: Set<PayrollLegalArbitratorV2.Source>
    )

    fun calculate(
        sessions: List<WorkSessionV2>,
        year: Int,
        monthZeroBased: Int,
        acceptedEmployerIds: Set<String>,
        arbitration: MealBasketLegalArbitrationBridgeV2.Result,
        amountContextsBySubject: Map<String, ConventionMealBasketEvaluatorV2.AmountContext> = emptyMap(),
        facts: FactDefaults = FactDefaults(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result {
        require(monthZeroBased in 0..11) { "Mois invalide" }
        if (!arbitration.reliable) {
            return Result(0, null, false, arbitration.warnings.distinct(), emptySet())
        }
        if (acceptedEmployerIds.isEmpty()) {
            return Result(0, 0.0, true, arbitration.warnings.distinct(), arbitration.selected.map { it.source }.toSet())
        }

        val normalizedAmountContexts = amountContextsBySubject.mapKeys {
            MealBasketLegalArbitrationBridgeV2.subject(it.key)
        }
        val targetMonth = YearMonth.of(year, monthZeroBased + 1)
        val awardsPerSubjectDay = mutableMapOf<Pair<String, LocalDate>, Int>()
        var count = 0
        var total = 0.0
        var reliable = true
        val warnings = arbitration.warnings.toMutableList()

        sessions.asSequence()
            .filter { it.employerId in acceptedEmployerIds && it.realExitMs != null }
            .sortedBy { it.countedEntryMs ?: it.realArrivalMs ?: Long.MAX_VALUE }
            .forEach sessionLoop@ { session ->
                val sessionFacts = workFacts(session, facts, zoneId)
                if (sessionFacts == null) {
                    reliable = false
                    warnings += "Panier : session ${session.id} non exploitable (horaires ou pauses incomplets) ; total mensuel non certifié."
                    return@sessionLoop
                }
                val day = sessionFacts.shiftStart.toLocalDate()
                if (YearMonth.from(day) != targetMonth) return@sessionLoop

                arbitration.selected.forEach selectedLoop@ { selected ->
                    val spec = selected.branchRule?.let {
                        ConventionMealBasketEvaluatorV2.RuleSpec(
                            it.deliveryMode, it.amountFormula, it.eligibilityAnyOf, it.blockers
                        )
                    } ?: selected.companyRule?.let {
                        ConventionMealBasketEvaluatorV2.RuleSpec(
                            it.deliveryMode, it.amountFormula, it.eligibilityAnyOf, it.blockers
                        )
                    } ?: run {
                        reliable = false
                        warnings += "Panier ${selected.subject} : règle arbitrée absente ; total bloqué."
                        return@selectedLoop
                    }

                    val amountContext = normalizedAmountContexts[selected.subject]
                        ?: ConventionMealBasketEvaluatorV2.AmountContext()
                    val evaluated = ConventionMealBasketEvaluatorV2.evaluate(spec, sessionFacts, amountContext)
                    warnings += evaluated.warnings
                    if (!evaluated.reliable) {
                        reliable = false
                        return@selectedLoop
                    }
                    if (evaluated.eligible != true || evaluated.cashAmount == null) return@selectedLoop

                    val maxPerDay = selected.branchRule?.maxAwardsPerCalendarDay
                        ?: selected.companyRule?.maxAwardsPerCalendarDay
                        ?: 1
                    val counting = selected.branchRule?.countingUnit
                        ?: selected.companyRule?.countingUnit
                        ?: ConventionMealBasketV2.CountingUnit.SHIFT
                    val key = selected.subject to day
                    val already = awardsPerSubjectDay[key] ?: 0
                    val allowed = when (counting) {
                        ConventionMealBasketV2.CountingUnit.WORKED_DAY -> already == 0
                        ConventionMealBasketV2.CountingUnit.SHIFT -> already < maxPerDay
                    }
                    if (allowed) {
                        awardsPerSubjectDay[key] = already + 1
                        count++
                        total += evaluated.cashAmount
                    }
                }
            }

        return Result(
            count = count,
            totalAmount = total.takeIf { reliable },
            reliable = reliable,
            warnings = warnings.distinct(),
            selectedSources = arbitration.selected.map { it.source }.toSet()
        )
    }

    private fun workFacts(
        session: WorkSessionV2,
        defaults: FactDefaults,
        zoneId: ZoneId
    ): ConventionMealBasketEvaluatorV2.WorkFacts? {
        val rawEntry = WorkTimePolicyV2.repairKnownCountedEntry(session.realArrivalMs, session.countedEntryMs)
            ?: session.countedEntryMs ?: session.realArrivalMs ?: return null
        val rawExit = session.countedExitMs ?: session.realExitMs ?: return null
        if (rawExit <= rawEntry) return null

        val shiftStart = toLocal(rawEntry, zoneId)
        val shiftEnd = toLocal(rawExit, zoneId)
        val pauseRanges = session.pauses.map { pause ->
            val end = pause.endMs ?: return null
            maxOf(rawEntry, pause.startMs) to minOf(rawExit, end)
        }.filter { (start, end) -> end > start }
            .sortedBy { it.first }
        if (pauseRanges.zipWithNext().any { (a, b) -> b.first < a.second }) return null

        val effective = mutableListOf<ConventionMealBasketEvaluatorV2.WorkInterval>()
        var cursor = rawEntry
        pauseRanges.forEach { (pauseStart, pauseEnd) ->
            if (pauseStart > cursor) {
                effective += ConventionMealBasketEvaluatorV2.WorkInterval(
                    toLocal(cursor, zoneId),
                    toLocal(pauseStart, zoneId)
                )
            }
            cursor = maxOf(cursor, pauseEnd)
        }
        if (cursor < rawExit) {
            effective += ConventionMealBasketEvaluatorV2.WorkInterval(
                toLocal(cursor, zoneId),
                shiftEnd
            )
        }

        return ConventionMealBasketEvaluatorV2.WorkFacts(
            shiftStart = shiftStart,
            shiftEnd = shiftEnd,
            effectiveWork = effective,
            postedShiftWorker = defaults.postedShiftWorker,
            canReturnHomeForMeal = defaults.canReturnHomeForMeal,
            worksAwayFromUsualWorkplace = defaults.worksAwayFromUsualWorkplace,
            mustEatAtWorkplace = defaults.mustEatAtWorkplace,
            companyCanteenAvailable = defaults.companyCanteenAvailable,
            employerMealProvided = defaults.employerMealProvided,
            mealVoucherProvided = defaults.mealVoucherProvided,
            otherSameNatureMealBenefit = defaults.otherSameNatureMealBenefit,
            employerNightWindow = defaults.employerNightWindow
        )
    }

    private fun toLocal(epochMs: Long, zoneId: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDateTime()
}
