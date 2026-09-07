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
 * L'éligibilité doit donc être confirmée explicitement une fois par entreprise.
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
        morningEligibilityConfirmed: Boolean? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Result {
        require(monthZeroBased in 0..11) { "Mois invalide" }
        val safeAmount = sanitizeAmount(amountPerBasket)
        if (acceptedEmployerIds.isEmpty()) {
            return Result(0, safeAmount, 0.0, morningEligibilityConfirmed = morningEligibilityConfirmed)
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
        if (morningEligibilityConfirmed != true) {
            val warnings = buildList {
                if (detected > 0 && morningEligibilityConfirmed == null) {
                    add("Panier : $detected journée(s) de poste matin détectée(s), mais le droit au panier du matin n'est pas confirmé pour cette entreprise. Aucun panier n'est ajouté automatiquement.")
                }
            }
            return Result(
                count = 0,
                amountPerBasket = safeAmount,
                totalAmount = if (morningEligibilityConfirmed == false) 0.0 else null,
                warnings = warnings,
                detectedMorningDays = detected,
                morningEligibilityConfirmed = morningEligibilityConfirmed
            )
        }

        val warnings = buildList {
            if (detected > 0 && safeAmount == null) {
                add("Panier : $detected journée(s) de poste matin éligible(s), mais montant unitaire non renseigné.")
            }
        }
        return Result(
            count = detected,
            amountPerBasket = safeAmount,
            totalAmount = safeAmount?.times(detected),
            warnings = warnings,
            detectedMorningDays = detected,
            morningEligibilityConfirmed = true
        )
    }

    private fun sanitizeAmount(value: Double?): Double? =
        value?.takeIf { it.isFinite() && it >= 0.0 }
}
