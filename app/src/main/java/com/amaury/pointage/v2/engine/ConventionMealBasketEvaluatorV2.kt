package com.amaury.pointage.v2.engine

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Évalue uniquement les conditions factuelles d'une règle repas déjà vérifiée juridiquement. */
object ConventionMealBasketEvaluatorV2 {
    data class WorkInterval(val start: LocalDateTime, val end: LocalDateTime) {
        fun structurallyValid() = end.isAfter(start)
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
        val employerNightWindow: ConventionMealBasketV2.DailyWindow? = null
    ) {
        fun structurallyValid(): Boolean {
            if (!shiftEnd.isAfter(shiftStart)) return false
            if (effectiveWork.any { !it.structurallyValid() || it.start.isBefore(shiftStart) || it.end.isAfter(shiftEnd) }) return false
            val sorted = effectiveWork.sortedBy { it.start }
            if (sorted.zipWithNext().any { (a, b) -> b.start.isBefore(a.end) }) return false
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
        if (!rule.structurallyValid()) return unresolved("règle repas invalide")
        if (!facts.structurallyValid()) return unresolved("faits de travail invalides")

        val groups = rule.eligibilityAnyOf.map { group -> evaluateGroup(group, facts) }
        val eligibility = when {
            groups.any { it == true } -> true
            groups.all { it == false } -> false
            else -> null
        }
        if (eligibility == false) {
            return Result(true, false, true, 0.0, listOf("Panier / indemnité repas : conditions vérifiées non remplies."))
        }
        if (eligibility == null) return unresolved("condition factuelle requise inconnue")

        val blockers = rule.blockers.associateWith(facts::blockerValue)
        if (blockers.any { it.value == true }) {
            return Result(true, false, true, 0.0, listOf("Panier / indemnité repas : avantage de même nature non cumulable déjà fourni."))
        }
        if (blockers.any { it.value == null }) return unresolved("non-cumul avec un avantage repas à confirmer")

        if (rule.deliveryMode == ConventionMealBasketV2.DeliveryMode.EMPLOYER_MEAL_OR_CASH_IF_NOT_PROVIDED) {
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

        val amount = resolveAmount(rule.amountFormula, amountContext)
        if (amount == null) {
            val reason = when (rule.amountFormula) {
                is ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple -> "minimum garanti applicable manquant"
                ConventionMealBasketV2.AmountFormula.ExternalAgreementAmount -> "montant de l'accord externe manquant"
                is ConventionMealBasketV2.AmountFormula.FixedEuro -> "montant fixe invalide"
            }
            return Result(true, true, false, null, listOf("Panier / indemnité repas : droit confirmé mais $reason ; aucun montant n'est inventé."))
        }
        return Result(true, true, true, amount, listOf("Panier / indemnité repas calculé uniquement depuis la règle vérifiée."))
    }

    private fun evaluateGroup(
        group: ConventionMealBasketV2.EligibilityGroup,
        facts: WorkFacts
    ): Boolean? {
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

    private fun evaluateCondition(
        condition: ConventionMealBasketV2.Condition,
        facts: WorkFacts
    ): Boolean? = when (condition) {
        ConventionMealBasketV2.Condition.WorkedDay -> facts.effectiveWork.isNotEmpty()
        ConventionMealBasketV2.Condition.PostedShiftWorker -> facts.postedShiftWorker
        ConventionMealBasketV2.Condition.UnableToReturnHomeForMeal -> facts.canReturnHomeForMeal?.not()
        ConventionMealBasketV2.Condition.WorksAwayFromUsualWorkplace -> facts.worksAwayFromUsualWorkplace
        ConventionMealBasketV2.Condition.MustEatAtWorkplace -> facts.mustEatAtWorkplace
        ConventionMealBasketV2.Condition.ShiftEnclosesMidnight -> shiftEnclosesMidnight(facts)
        ConventionMealBasketV2.Condition.ShiftStartsAtMidnight -> facts.shiftStart.toLocalTime() == LocalTime.MIDNIGHT
        is ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInFixedWindow ->
            effectiveMinutesInWindow(facts, condition.window) >= condition.minimumMinutes
        is ConventionMealBasketV2.Condition.MinimumEffectiveMinutesInEmployerWindow -> {
            val actual = facts.employerNightWindow
            if (actual == null) {
                null
            } else if (
                actual.durationMinutes() != condition.requiredWindowMinutes ||
                !actual.containedIn(condition.allowedEnvelope)
            ) {
                null
            } else {
                effectiveMinutesInWindow(facts, actual) >= condition.minimumEffectiveMinutes
            }
        }
        is ConventionMealBasketV2.Condition.ShiftStartsOrEndsInWindow -> {
            val start = facts.shiftStart.hour * 60 + facts.shiftStart.minute
            val end = facts.shiftEnd.hour * 60 + facts.shiftEnd.minute
            condition.window.containsMinute(start) || condition.window.containsMinute(end) ||
                start == condition.window.endMinute || end == condition.window.endMinute
        }
    }

    private fun shiftEnclosesMidnight(facts: WorkFacts): Boolean {
        var midnight = facts.shiftStart.toLocalDate().plusDays(1).atStartOfDay()
        while (!midnight.isAfter(facts.shiftEnd)) {
            if (facts.shiftStart.isBefore(midnight) && facts.shiftEnd.isAfter(midnight)) return true
            midnight = midnight.plusDays(1)
        }
        return false
    }

    private fun effectiveMinutesInWindow(
        facts: WorkFacts,
        window: ConventionMealBasketV2.DailyWindow
    ): Int {
        if (!window.structurallyValid()) return 0
        var total = 0L
        var date = facts.shiftStart.toLocalDate().minusDays(1)
        val last = facts.shiftEnd.toLocalDate().plusDays(1)
        while (!date.isAfter(last)) {
            val windowStart = date.atStartOfDay().plusMinutes(window.startMinute.toLong())
            val windowEnd = if (window.crossesMidnight) {
                date.plusDays(1).atStartOfDay().plusMinutes(window.endMinute.toLong())
            } else {
                date.atStartOfDay().plusMinutes(window.endMinute.toLong())
            }
            facts.effectiveWork.forEach { interval ->
                val start = maxOf(interval.start, windowStart)
                val end = minOf(interval.end, windowEnd)
                if (end.isAfter(start)) total += ChronoUnit.MINUTES.between(start, end)
            }
            date = date.plusDays(1)
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun resolveAmount(
        formula: ConventionMealBasketV2.AmountFormula,
        context: AmountContext
    ): Double? = when (formula) {
        is ConventionMealBasketV2.AmountFormula.FixedEuro -> formula.amount.takeIf { formula.structurallyValid() }
        is ConventionMealBasketV2.AmountFormula.MinimumGuaranteedMultiple -> context.minimumGuaranteed
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?.times(formula.multiplier)
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
