package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*

data class OvertimeTierV2(val fromMinutes:Int,val toMinutes:Int?,val multiplier:Double)

data class PayrollRulesV2(
    /** Seuil hebdomadaire confirmé par contrat/règle. Null = inconnu. */
    val weeklyRegularMinutes:Int? = null,
    /** Aucun palier n'est inventé : ils doivent venir d'une règle confirmée. */
    val overtimeTiers:List<OvertimeTierV2> = emptyList(),
    val nightMultiplier:Double? = null,
    val saturdayMultiplier:Double? = null,
    val sundayMultiplier:Double? = null
)

data class PayrollWeekV2(val paidMinutes:Int,val nightMinutes:Int=0,val saturdayMinutes:Int=0,val sundayMinutes:Int=0)

data class PayrollResultV2(
    val regularGross:Double,
    val overtimeGross:Double,
    val premiumsGross:Double,
    val fixedPremiumsGross:Double,
    val baskets:Double,
    val grossEstimate:Double,
    val deductions:Double,
    val netBeforeUnknownContributions:Double,
    val traces:List<String>
)

/**
 * Calcul déterministe : aucune durée ou majoration n'est supposée.
 * Le seuil hebdomadaire vient d'abord d'une règle confirmée, sinon du contrat.
 * Les majorations restent à zéro tant qu'aucun palier n'a été fourni.
 */
object PayrollEngineV2 {
    fun calculate(
        contract:ContractV2,
        weeks:List<PayrollWeekV2>,
        rules:PayrollRulesV2,
        premiums:List<PremiumV2> = emptyList(),
        baskets:List<BasketV2> = emptyList(),
        deductions:List<DeductionV2> = emptyList()
    ):PayrollResultV2 {
        val rate = requireNotNull(contract.grossHourlyRate) { "Taux horaire brut obligatoire" }
        require(rate > 0.0) { "Taux horaire brut invalide" }

        val regularLimit = rules.weeklyRegularMinutes
            ?: contract.contractualWeeklyMinutes
            ?: error("Durée hebdomadaire contractuelle/règle obligatoire")
        require(regularLimit > 0) { "Durée hebdomadaire invalide" }

        var regularMinutes = 0
        var overtimeGross = 0.0
        var extras = 0.0
        val trace = mutableListOf<String>()

        weeks.forEach { week ->
            val paid = week.paidMinutes.coerceAtLeast(0)
            val regular = minOf(paid, regularLimit)
            regularMinutes += regular

            rules.overtimeTiers.forEach { tier ->
                require(tier.fromMinutes >= regularLimit) { "Palier d'heures supplémentaires incohérent" }
                require(tier.multiplier >= 1.0) { "Multiplicateur d'heures supplémentaires invalide" }
                val end = tier.toMinutes ?: Int.MAX_VALUE
                val minutes = (minOf(paid, end) - maxOf(regularLimit, tier.fromMinutes)).coerceAtLeast(0)
                if (minutes > 0) overtimeGross += minutes / 60.0 * rate * tier.multiplier
            }

            rules.nightMultiplier?.let { multiplier ->
                require(multiplier >= 1.0)
                if (week.nightMinutes > 0) extras += week.nightMinutes / 60.0 * rate * (multiplier - 1.0)
            }
            rules.saturdayMultiplier?.let { multiplier ->
                require(multiplier >= 1.0)
                if (week.saturdayMinutes > 0) extras += week.saturdayMinutes / 60.0 * rate * (multiplier - 1.0)
            }
            rules.sundayMultiplier?.let { multiplier ->
                require(multiplier >= 1.0)
                if (week.sundayMinutes > 0) extras += week.sundayMinutes / 60.0 * rate * (multiplier - 1.0)
            }
        }

        val regularGross = regularMinutes / 60.0 * rate
        val fixed = premiums.sumOf { it.amount }
        val basketTotal = baskets.sumOf { it.amount }
        val gross = regularGross + overtimeGross + extras + fixed
        val deductionsTotal = deductions.sumOf { it.amount }.coerceAtLeast(0.0)

        trace += "Temps payé V2 + durée contractuelle/règles confirmées"
        if (rules.overtimeTiers.isEmpty()) trace += "Aucune majoration d'heures supplémentaires appliquée : règle non fournie"
        if (baskets.isNotEmpty()) trace += "Paniers suivis séparément du brut estimé"

        return PayrollResultV2(
            regularGross,
            overtimeGross,
            extras,
            fixed,
            basketTotal,
            gross,
            deductionsTotal,
            (gross - deductionsTotal).coerceAtLeast(0.0),
            trace
        )
    }
}
