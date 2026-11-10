package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*
import java.math.BigDecimal
import java.math.RoundingMode
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
    /** Same decimal cents boundary as payroll; finite alone does not prevent saturation. */
    fun isValidAmount(value: Double): Boolean = value >= 0.0 && PayrollMoneyV2.cents(value) != null

    /** Preserve original decimal cents before binary conversion; bound text work as well as value. */
    fun parseAmount(raw: String): Double? {
        if (raw.length > 256) return null
        val decimal = raw.toBigDecimalOrNull() ?: return null
        if (decimal.signum() < 0 || decimal.scale() !in -400..400) return null
        val value = decimal.toDouble()
        val cents = PayrollMoneyV2.cents(value) ?: return null
        val originalCents = decimal.movePointRight(2).setScale(0, RoundingMode.HALF_UP)
        return value.takeIf { originalCents.compareTo(BigDecimal.valueOf(cents)) == 0 }
    }

    fun compare(expected:Map<String,Double>, observed:Map<String,Double>, tolerance:Double=0.02):PayslipComparisonV2 {
        require(isValidAmount(tolerance)) { "Tolérance de comparaison invalide" }
        require((expected.values + observed.values).all { isValidAmount(it) }) {
            "Montant de comparaison invalide"
        }
        val toleranceCents=requireNotNull(PayrollMoneyV2.cents(tolerance))
        val keys=(expected.keys+observed.keys).toSortedSet()
        val out=keys.mapNotNull { key ->
            val e=expected[key]
            val o=observed[key]
            val exceedsTolerance=e!=null&&o!=null&&
                abs(requireNotNull(PayrollMoneyV2.cents(e))-requireNotNull(PayrollMoneyV2.cents(o)))>toleranceCents
            if(e==null || o==null || exceedsTolerance) DiscrepancyV2(
                id="$key:${e ?: "missing"}:${o ?: "missing"}",category=key,expected=e,observed=o,
                explanation=when { e==null -> "Valeur inattendue sur le bulletin"; o==null -> "Valeur attendue absente ou non lue"; o<e -> "Écart négatif à vérifier"; else -> "Écart positif à vérifier" }
            ) else null
        }
        return PayslipComparisonV2(out,out.isEmpty())
    }

    fun evolve(discrepancy:DiscrepancyV2,status:DiscrepancyStatusV2,explanation:String?=discrepancy.explanation)=discrepancy.copy(status=status,explanation=explanation)
}
