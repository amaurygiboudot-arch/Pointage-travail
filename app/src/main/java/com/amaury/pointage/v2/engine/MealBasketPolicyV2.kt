package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Source unique V2 du droit au panier lié au poste du matin.
 *
 * Un panier au maximum est compté par journée civile et par entreprise, même si la
 * journée contient plusieurs sessions. Le panier reste séparé du brut cotisable.
 */
object MealBasketPolicyV2 {
    data class Result(
        val count: Int,
        val amountPerBasket: Double?,
        val totalAmount: Double?,
        val warnings: List<String> = emptyList()
    )

    fun calculate(
        sessions: List<WorkSessionV2>,
        year: Int,
        monthZeroBased: Int,
        acceptedEmployerIds: Set<String>,
        amountPerBasket: Double?,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result {
        require(monthZeroBased in 0..11) { "Mois invalide" }
        if (acceptedEmployerIds.isEmpty()) {
            return Result(0, sanitizeAmount(amountPerBasket), 0.0)
        }

        val targetMonth = YearMonth.of(year, monthZeroBased + 1)
        val morningDays = linkedSetOf<java.time.LocalDate>()

        sessions.asSequence()
            .filter { it.employerId in acceptedEmployerIds && it.realExitMs != null }
            .forEach { session ->
                val effectiveEntry = WorkTimePolicyV2.repairKnownCountedEntry(
                    session.realArrivalMs,
                    session.countedEntryMs
                ) ?: session.countedEntryMs ?: session.realArrivalMs ?: return@forEach

                val date = Instant.ofEpochMilli(effectiveEntry).atZone(zoneId).toLocalDate()
                if (YearMonth.from(date) != targetMonth) return@forEach
                if (WorkTimePolicyV2.hasAutomaticMorningBasket(effectiveEntry)) morningDays += date
            }

        val count = morningDays.size
        val safeAmount = sanitizeAmount(amountPerBasket)
        val warnings = buildList {
            if (count > 0 && safeAmount == null) {
                add("Panier : $count journée(s) de poste matin détectée(s), mais montant unitaire non renseigné.")
            }
        }
        return Result(
            count = count,
            amountPerBasket = safeAmount,
            totalAmount = safeAmount?.times(count),
            warnings = warnings
        )
    }

    private fun sanitizeAmount(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it >= 0.0 }
}
