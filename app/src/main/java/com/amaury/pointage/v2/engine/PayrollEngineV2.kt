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
        if (contract.type == ContractTypeV2.FORFAIT_DAYS || contract.type == ContractTypeV2.FORFAIT_HOURS) {
            return calculateForfait(contract, premiums, baskets, deductions)
        }

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

    private fun calculateForfait(
        contract:ContractV2,
        premiums:List<PremiumV2>,
        baskets:List<BasketV2>,
        deductions:List<DeductionV2>
    ):PayrollResultV2 {
        val monthlyGross = requireNotNull(contract.monthlyGrossSalary) {
            "Salaire brut mensuel convenu obligatoire pour un forfait"
        }
        require(monthlyGross > 0.0) { "Salaire brut mensuel convenu invalide" }

        when (contract.type) {
            ContractTypeV2.FORFAIT_HOURS -> {
                requireNotNull(contract.forfaitHoursPeriod) { "Période du forfait heures obligatoire" }
                val hours = requireNotNull(contract.forfaitHours) { "Nombre d'heures du forfait obligatoire" }
                require(hours > 0.0) { "Nombre d'heures du forfait invalide" }
            }
            ContractTypeV2.FORFAIT_DAYS -> {
                val days = requireNotNull(contract.forfaitAnnualDays) { "Nombre annuel de jours obligatoire" }
                require(days > 0.0 && days <= 218.0) { "Nombre annuel de jours du forfait invalide" }
            }
            else -> error("Type de forfait incohérent")
        }

        val fixed = premiums.sumOf { it.amount }
        val basketTotal = baskets.sumOf { it.amount }
        val gross = monthlyGross + fixed
        val deductionsTotal = deductions.sumOf { it.amount }.coerceAtLeast(0.0)
        val trace = mutableListOf<String>()
        trace += when (contract.type) {
            ContractTypeV2.FORFAIT_HOURS -> "Forfait heures : salaire brut mensuel convenu utilisé comme base ; les heures du forfait ne sont pas reconverties artificiellement en taux horaire."
            ContractTypeV2.FORFAIT_DAYS -> "Forfait jours : salaire brut mensuel convenu utilisé comme base ; aucun taux horaire n'est inventé."
            else -> error("Type de forfait incohérent")
        }
        trace += "Les pointages servent au suivi du temps/charge et ne recalculent pas automatiquement la rémunération forfaitaire."
        if (baskets.isNotEmpty()) trace += "Paniers suivis séparément du brut estimé"

        return PayrollResultV2(
            regularGross = monthlyGross,
            overtimeGross = 0.0,
            premiumsGross = 0.0,
            fixedPremiumsGross = fixed,
            baskets = basketTotal,
            grossEstimate = gross,
            deductions = deductionsTotal,
            netBeforeUnknownContributions = (gross - deductionsTotal).coerceAtLeast(0.0),
            traces = trace
        )
    }
}
