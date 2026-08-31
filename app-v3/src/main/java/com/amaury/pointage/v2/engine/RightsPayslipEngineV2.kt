package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*
import kotlin.math.abs

data class RightsSnapshotV2(val counters:List<CounterV2>,val warnings:List<String>)
object RightsEngineV2 {
    fun snapshot(counters:List<CounterV2>, nowMs:Long):RightsSnapshotV2 {
        val warnings=counters.filter{nowMs !in it.referenceStartMs..it.referenceEndMs}.map{"Compteur ${it.label} hors période de référence"}
        return RightsSnapshotV2(counters,warnings)
    }
}

data class PayslipComparisonV2(val discrepancies:List<DiscrepancyV2>,val conforming:Boolean)
object PayslipEngineV2 {
    fun compare(expected:Map<String,Double>, observed:Map<String,Double>, tolerance:Double=0.02):PayslipComparisonV2 {
        val keys=(expected.keys+observed.keys).toSortedSet()
        val out=keys.mapNotNull { key ->
            val e=expected[key]
            val o=observed[key]
            if(e==null || o==null || abs(e-o)>tolerance) DiscrepancyV2(
                id="$key:${e ?: "missing"}:${o ?: "missing"}",category=key,expected=e,observed=o,
                explanation=when { e==null -> "Valeur inattendue sur le bulletin"; o==null -> "Valeur attendue absente ou non lue"; o<e -> "Écart négatif à vérifier"; else -> "Écart positif à vérifier" }
            ) else null
        }
        return PayslipComparisonV2(out,out.isEmpty())
    }

    fun evolve(discrepancy:DiscrepancyV2,status:DiscrepancyStatusV2,explanation:String?=discrepancy.explanation)=discrepancy.copy(status=status,explanation=explanation)
}
