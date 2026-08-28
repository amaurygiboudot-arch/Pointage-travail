package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*

data class OvertimeTierV2(val fromMinutes:Int,val toMinutes:Int?,val multiplier:Double)
data class PayrollRulesV2(
    val weeklyRegularMinutes:Int=35*60,
    val overtimeTiers:List<OvertimeTierV2> = listOf(OvertimeTierV2(35*60,43*60,1.25),OvertimeTierV2(43*60,null,1.50)),
    val nightMultiplier:Double?=null,
    val saturdayMultiplier:Double?=null,
    val sundayMultiplier:Double?=null
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

/** Aucun pourcentage légal/conventionnel n'est codé ici: les multiplicateurs arrivent de règles validées. */
object PayrollEngineV2 {
    fun calculate(
        contract:ContractV2,
        weeks:List<PayrollWeekV2>,
        rules:PayrollRulesV2,
        premiums:List<PremiumV2> = emptyList(),
        baskets:List<BasketV2> = emptyList(),
        deductions:List<DeductionV2> = emptyList()
    ):PayrollResultV2 {
        val rate=requireNotNull(contract.grossHourlyRate){"Taux horaire brut obligatoire"}
        require(rate>0.0)
        var regularMinutes=0
        var overtimeGross=0.0
        var extras=0.0
        val trace=mutableListOf<String>()
        weeks.forEach { week ->
            val regular=minOf(week.paidMinutes,rules.weeklyRegularMinutes).coerceAtLeast(0)
            regularMinutes += regular
            rules.overtimeTiers.forEach { tier ->
                val end=tier.toMinutes ?: Int.MAX_VALUE
                val minutes=(minOf(week.paidMinutes,end)-maxOf(rules.weeklyRegularMinutes,tier.fromMinutes)).coerceAtLeast(0)
                if(minutes>0) overtimeGross += minutes/60.0*rate*tier.multiplier
            }
            rules.nightMultiplier?.let { if(week.nightMinutes>0) extras += week.nightMinutes/60.0*rate*(it-1.0) }
            rules.saturdayMultiplier?.let { if(week.saturdayMinutes>0) extras += week.saturdayMinutes/60.0*rate*(it-1.0) }
            rules.sundayMultiplier?.let { if(week.sundayMinutes>0) extras += week.sundayMinutes/60.0*rate*(it-1.0) }
        }
        val regularGross=regularMinutes/60.0*rate
        val fixed=premiums.sumOf{it.amount}
        val basketTotal=baskets.sumOf{it.amount}
        val gross=regularGross+overtimeGross+extras+fixed
        val deductionsTotal=deductions.sumOf{it.amount}.coerceAtLeast(0.0)
        trace += "Calcul déterministe depuis temps payé + règles confirmées"
        if(baskets.isNotEmpty()) trace += "Paniers suivis séparément du brut estimé"
        return PayrollResultV2(regularGross,overtimeGross,extras,fixed,basketTotal,gross,deductionsTotal,(gross-deductionsTotal).coerceAtLeast(0.0),trace)
    }
}
