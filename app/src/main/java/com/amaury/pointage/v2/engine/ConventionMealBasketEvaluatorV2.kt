package com.amaury.pointage.v2.engine

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Évalue uniquement les conditions factuelles d'une règle repas déjà vérifiée juridiquement. */
object ConventionMealBasketEvaluatorV2 {
    data class WorkInterval(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val startInstant: Instant? = null,
        val endInstant: Instant? = null
    ) {
        fun structurallyValid(): Boolean = when {
            startInstant != null && endInstant != null -> endInstant.isAfter(startInstant)
            startInstant != null || endInstant != null -> false
            else -> end.isAfter(start)
        }
    }

    data class WorkFacts(
        val shiftStart: LocalDateTime,
        val shiftEnd: LocalDateTime,
        val effectiveWork: List<WorkInterval>,
        val postedShiftWorker: Boolean? = null,
        val canReturnHomeForMeal: Boolean? = null,
        val worksAwayFromUsualWorkplace: Boolean? = null,
        val mustEatAtWorkplace: Boolean? = null,
        val companyCanteenAvailable: Boolean? = null,
        val employerMealProvided: Boolean? = null,
        val mealVoucherProvided: Boolean? = null,
        val otherSameNatureMealBenefit: Boolean? = null,
        val employerNightWindow: ConventionMealBasketV2.DailyWindow? = null,
        val shiftStartInstant: Instant? = null,
        val shiftEndInstant: Instant? = null,
        val zoneId: ZoneId? = null,
        /** Pause fixe importée : durée connue, position inconnue. */
        val unlocatedUnpaidPauseMs: Long = 0L
    ) {
        fun structurallyValid(): Boolean {
            if (unlocatedUnpaidPauseMs < 0L) return false
            val hasInstantShift = shiftStartInstant != null && shiftEndInstant != null && zoneId != null
            if (hasInstantShift) {
                if (!shiftEndInstant!!.isAfter(shiftStartInstant)) return false
                if (effectiveWork.any { interval ->
                        !interval.structurallyValid() || interval.startInstant == null || interval.endInstant == null ||
                            interval.startInstant.isBefore(shiftStartInstant) || interval.endInstant.isAfter(shiftEndInstant)
                    }) return false
                val sorted = effectiveWork.sortedBy { it.startInstant }
                if (sorted.zipWithNext().any { (a, b) -> b.startInstant!!.isBefore(a.endInstant) }) return false
                val effectiveMs = sorted.sumOf { Duration.between(it.startInstant, it.endInstant).toMillis() }
                if (unlocatedUnpaidPauseMs > effectiveMs) return false
            } else {
                if (shiftStartInstant != null || shiftEndInstant != null || zoneId != null) return false
                if (!shiftEnd.isAfter(shiftStart)) return false
                if (effectiveWork.any { !it.structurallyValid() || it.start.isBefore(shiftStart) || it.end.isAfter(shiftEnd) }) return false
                val sorted = effectiveWork.sortedBy { it.start }
                if (sorted.zipWithNext().any { (a, b) -> b.start.isBefore(a.end) }) return false
                val effectiveMs = sorted.sumOf { ChronoUnit.MILLIS.between(it.start, it.end) }
                if (unlocatedUnpaidPauseMs > effectiveMs) return false
            }
            return employerNightWindow?.structurallyValid() != false
        }

        fun blockerValue(blocker: ConventionMealBasketV2.Blocker): Boolean? = when (blocker) {
            ConventionMealBasketV2.Blocker.COMPANY_CANTEEN -> companyCanteenAvailable
            ConventionMealBasketV2.Blocker.EMPLOYER_PROVIDED_MEAL -> employerMealProvided
            ConventionMealBasketV2.Blocker.MEAL_VOUCHER -> mealVoucherProvided
            ConventionMealBasketV2.Blocker.OTHER_SAME_NATURE_MEAL_BENEFIT -> otherSameNatureMealBenefit
        }
    }

    data class AmountContext(
        val minimumGuaranteed: Double? = null,
        val externalAgreementAmount: Double? = null
    )

    data class RuleSpec(
        val deliveryMode: ConventionMealBasketV2.DeliveryMode,
        val amountFormula: ConventionMealBasketV2.AmountFormula,
        val eligibilityAnyOf: List<ConventionMealBasketV2.EligibilityGroup>,
        val blockers: Set<ConventionMealBasketV2.Blocker>
    ) {
        fun structurallyValid(): Boolean = amountFormula.structurallyValid() &&
            eligibilityAnyOf.isNotEmpty() && eligibilityAnyOf.all { it.structurallyValid() }
    }

    data class Result(
        val eligibilityConfirmed: Boolean,
        val eligible: Boolean?,
        val reliable: Boolean,
        val cashAmount: Double?,
        val warnings: List<String>
    )

    fun evaluate(
        rule: ConventionMealBasketV2.Rule,
        facts: WorkFacts,
        amountContext: AmountContext = AmountContext()
    ): Result {
        if (!rule.structurallyValid()) return unresolved("règle repas KALI invalide")
        return evaluate(
            RuleSpec(rule.deliveryMode, rule.amountFormula, rule.eligibilityAnyOf, rule.blockers),
            facts,
            amountContext
        )
    }

    fun evaluate(
        spec: RuleSpec,
        facts: WorkFacts,
        amountContext: AmountContext = AmountContext()
    ): Result {
        if (!spec.structurallyValid()) return unresolved("règle repas structurée invalide")
        if (!facts.structurallyValid()) return unresolved("faits de travail invalides")

        val groups = spec.eligibilityAnyOf.map { group -> evaluateGroup(group, facts) }
        val eligibility = when {
            groups.any { it == true } -> true
            groups.all { it == false } -> false
            else -> null
        }
        if (eligibility == false) {
            return Result(true, false, true, 0.0, listOf("Panier / indemnité repas : conditions vérifiées non remplies."))
        }
        if (eligibility == null) return unresolved("condition factuelle ou position de pause requise inconnue")

        val blockers = spec.blockers.associateWith(facts::blockerValue)
        if (blockers.any { it.value == true }) {
            return Result(true, false, true, 0.0, listOf("Panier / indemnité repas : avantage de même nature non cumulable déjà fourni."))
        }
        if (blockers.any { it.value == null }) return unresolved("non-cumul avec un avantage repas à confirmer")

        if (spec.deliveryMode == ConventionMealBasketV2.DeliveryMode.EMPLOYER_MEAL_OR_CASH_IF_NOT_PROVIDED) {
            when (facts.employerMealProvided) {
                true -> return Result(
                    eligibilityConfirmed = true,
                    eligible = true,
                    reliable = true,
                    cashAmount = 0.0,
                    warnings = listOf("Panier / indemnité repas : droit satisfait par le repas fourni par l'employeur.")
                )
                null -> return unresolved("repas employeur à confirmer avant l'indemnité alternative")
                false -> Unit
            }
        }

        val amount = resolveAmount(spec.amountFormula, amountContext)
        if (amount == null) {
            val reason = when (spec.amountFormula) {
                is ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple -> "minimum garanti applicable manquant"
                ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount -> "montant de l'accord externe manquant"
                is ConventionMealBasketV2.AmountFormula.FixedEuro -> "montant fixe invalide"
            }
            return Result(true, true, false, null, listOf("Panier / indemnité repas : droit confirmé mais $reason ; aucun montant n'est inventé."))
        }
        return Result(true, true, true, amount, listOf("Panier / indemnité repas calculé uniquement depuis la règle vérifiée."))
    }

    private fun evaluateGroup(group: ConventionMealBasketV2.EligibilityGroup, facts: WorkFacts): Boolean? {
        if (!group.structurallyValid()) return null
        var unknown = false
        group.allOf.forEach { condition ->
            when (evaluateCondition(condition, facts)) {
                false -> return false
                null -> unknown = true
                true -> Unit
            }
        }
        return if (unknown) null else true
    }

    private fun evaluateCondition(condition: ConventionMealBasketV2.Condition, facts: WorkFacts): Boolean? = when (condition) {
        ConventionMealBasketV2.Condition.WorkedDay -> definitelyWorked(facts)
        ConventionMealBasketV2.Condition.PostedShiftWorker -> facts.postedShiftWorker
        ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal -> facts.canReturnHomeForMeal?.not()
        ConventionMealBasketV2.Condition.WorksAwayFromUsualWorkplace -> facts.worksAwayFromUsualWorkplace
        ConventionMealBasketV2.Condition.MustEatAtWorkplace -> facts.mustEatAtWorkplace
        ConventionMealBasketV2.Condition.ShiftEnclosesMidnight -> shiftEnclosesMidnight(facts)
        ConventionMealBasketV2.Condition.ShiftStartsAtMidnight -> facts.shiftStart.toLocalTime() == LocalTime.MIDNIGHT
        is ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow ->
            thresholdResult(facts, effectiveMillisInWindow(facts, condition.window), condition.minimumMinutes.toLong() * 60_000L)
        is ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow -> {
            val actual = facts.employerNightWindow
            if (actual == null) null
            else if (actual.durationMinutes() != condition.requiredWindowMinutes || !actual.containedIn(condition.allowedEnvelope)) null
            else thresholdResult(
                facts,
                effectiveMillisInWindow(facts, actual),
                condition.minimumEffectiveMinutes.toLong() * 60_000L
            )
        }
        is ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow -> {
            val start = facts.shiftStart.hour * 60 + facts.shiftStart.minute
            val end = facts.shiftEnd.hour * 60 + facts.shiftEnd.minute
            condition.window.containsMinute(start) || condition.window.containsMinute(end) ||
                start == condition.window.endMinute || end == condition.window.endMinute
        }
    }

    private fun definitelyWorked(facts: WorkFacts): Boolean {
        val total = facts.effectiveWork.sumOf { interval ->
            if (interval.startInstant != null && interval.endInstant != null) {
                Duration.between(interval.startInstant, interval.endInstant).toMillis()
            } else ChronoUnit.MILLIS.between(interval.start, interval.end)
        }
        return total - facts.unlocatedUnpaidPauseMs > 0L
    }

    /**
     * Une pause importée de position inconnue peut se trouver entièrement dans la fenêtre.
     * Si le seuil est satisfait même dans ce pire cas => vrai ; s'il est impossible même sans la
     * pause => faux ; entre les deux => inconnu, donc calcul bloqué.
     */
    private fun thresholdResult(facts: WorkFacts, maximumWindowMs: Long, requiredMs: Long): Boolean? {
        if (maximumWindowMs < requiredMs) return false
        if (facts.unlocatedUnpaidPauseMs <= 0L) return true
        val guaranteedWindowMs = (maximumWindowMs - facts.unlocatedUnpaidPauseMs).coerceAtLeast(0L)
        return if (guaranteedWindowMs >= requiredMs) true else null
    }

    private fun shiftEnclosesMidnight(facts: WorkFacts): Boolean {
        var midnight = facts.shiftStart.toLocalDate().plusDays(1).atStartOfDay()
        while (!midnight.isAfter(facts.shiftEnd)) {
            if (facts.shiftStart.isBefore(midnight) && facts.shiftEnd.isAfter(midnight)) return true
            midnight = midnight.plusDays(1)
        }
        return false
    }

    private fun effectiveMillisInWindow(facts: WorkFacts, window: ConventionMealBasketV2.DailyWindow): Long {
        if (!window.structurallyValid()) return 0L
        return if (facts.zoneId != null && facts.effectiveWork.all { it.startInstant != null && it.endInstant != null }) {
            effectiveMillisInWindowByInstant(facts, window, facts.zoneId)
        } else {
            effectiveMillisInWindowByLocalTime(facts, window)
        }
    }

    private fun effectiveMillisInWindowByInstant(
        facts: WorkFacts,
        window: ConventionMealBasketV2.DailyWindow,
        zoneId: ZoneId
    ): Long {
        var total = 0L
        var date = facts.shiftStart.toLocalDate().minusDays(1)
        val last = facts.shiftEnd.toLocalDate().plusDays(1)
        while (!date.isAfter(last)) {
            val localStart = date.atStartOfDay().plusMinutes(window.startMinute.toLong())
            val localEnd = if (window.crossesMidnight) {
                date.plusDays(1).atStartOfDay().plusMinutes(window.endMinute.toLong())
            } else date.atStartOfDay().plusMinutes(window.endMinute.toLong())
            val windowStart = localStart.atZone(zoneId).toInstant()
            val windowEnd = localEnd.atZone(zoneId).toInstant()
            facts.effectiveWork.forEach { interval ->
                val start = maxOf(interval.startInstant!!, windowStart)
                val end = minOf(interval.endInstant!!, windowEnd)
                if (end.isAfter(start)) total += Duration.between(start, end).toMillis()
            }
            date = date.plusDays(1)
        }
        return total
    }

    private fun effectiveMillisInWindowByLocalTime(
        facts: WorkFacts,
        window: ConventionMealBasketV2.DailyWindow
    ): Long {
        var total = 0L
        var date = facts.shiftStart.toLocalDate().minusDays(1)
        val last = facts.shiftEnd.toLocalDate().plusDays(1)
        while (!date.isAfter(last)) {
            val windowStart = date.atStartOfDay().plusMinutes(window.startMinute.toLong())
            val windowEnd = if (window.crossesMidnight) {
                date.plusDays(1).atStartOfDay().plusMinutes(window.endMinute.toLong())
            } else date.atStartOfDay().plusMinutes(window.endMinute.toLong())
            facts.effectiveWork.forEach { interval ->
                val start = maxOf(interval.start, windowStart)
                val end = minOf(interval.end, windowEnd)
                if (end.isAfter(start)) total += ChronoUnit.MILLIS.between(start, end)
            }
            date = date.plusDays(1)
        }
        return total
    }

    private fun resolveAmount(formula: ConventionMealBasketV2.AmountFormula, context: AmountContext): Double? = when (formula) {
        is ConventionMealBasketV2.AmountFormula.FixedEuro -> formula.amount.takeIf { formula.structurallyValid() }
        is ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple -> context.minimumGuaranteed
            ?.takeIf { it.isFinite() && it >= 0.0 }?.times(formula.multiplier)
        ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount -> context.externalAgreementAmount
            ?.takeIf { it.isFinite() && it >= 0.0 }
    }

    private fun unresolved(reason: String) = Result(
        eligibilityConfirmed = false,
        eligible = null,
        reliable = false,
        cashAmount = null,
        warnings = listOf("Panier / indemnité repas : $reason ; aucun droit n'est inventé.")
    )
}