package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Source V2 du décompte des paniers liés au poste du matin.
 *
 * Le moteur sait détecter les journées de poste matin, mais cette détection ne
 * prouve pas à elle seule l'existence d'un droit au panier dans l'entreprise.
 * L'état entreprise est conservé sans nouvelle donnée incompatible :
 * - montant > 0 : panier du matin confirmé applicable ;
 * - montant = 0 : panier du matin confirmé non applicable ;
 * - montant absent : droit à confirmer.
 *
 * Un panier au maximum est compté par journée civile et par entreprise.
 * Le panier reste séparé du brut cotisable.
 */
object MealBasketPolicyV2 {
    data class Result(
        val count: Int,
        val amountPerBasket: Double?,
        val totalAmount: Double?,
        val warnings: List<String> = emptyList(),
        val detectedMorningDays: Int = 0,
        val morningEligibilityConfirmed: Boolean? = null
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
        val safeAmount = sanitizeAmount(amountPerBasket)
        val eligibility = when {
            safeAmount == null -> null
            safeAmount == 0.0 -> false
            else -> true
        }
        if (acceptedEmployerIds.isEmpty()) {
            return Result(
                count = 0,
                amountPerBasket = safeAmount,
                totalAmount = if (eligibility == false) 0.0 else null,
                morningEligibilityConfirmed = eligibility
            )
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

        val detected = morningDays.size
        if (eligibility != true) {
            val warnings = buildList {
                if (detected > 0 && eligibility == null) {
                    add("Panier : $detected journée(s) de poste matin détectée(s), mais le droit au panier du matin n'est pas confirmé pour cette entreprise. Aucun panier n'est ajouté automatiquement.")
                }
            }
            return Result(
                count = 0,
                amountPerBasket = safeAmount,
                totalAmount = if (eligibility == false) 0.0 else null,
                warnings = warnings,
                detectedMorningDays = detected,
                morningEligibilityConfirmed = eligibility
            )
        }

        val confirmedAmount = requireNotNull(safeAmount)
        return Result(
            count = detected,
            amountPerBasket = confirmedAmount,
            totalAmount = confirmedAmount * detected,
            detectedMorningDays = detected,
            morningEligibilityConfirmed = true
        )
    }

    private fun sanitizeAmount(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it >= 0.0 }
}
