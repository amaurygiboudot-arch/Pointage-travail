package com.amaury.pointage

import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2LegacyPolicy
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Façade historique de l'écran Salaire.
 * En V2, aucun calcul salarial n'est effectué ici : tous les résultats viennent
 * de V2SalaryAdapter, lui-même alimenté uniquement par PayrollEngineV2.
 */
object SalaryCalculator {
    data class TierResult(val label:String,val durationMs:Long,val multiplier:Double)

    data class Result(
        val regularMs:Long,
        val overtimeTiers:List<TierResult>,
        val totalWorkedMs:Long,
        val workedGross:Double,
        val monthlyBaseGross:Double,
        val overtimeGross:Double,
        val nightMs:Long,
        val nightPremiumGross:Double,
        val saturdayMs:Long,
        val saturdayPremiumGross:Double,
        val sundayMs:Long,
        val sundayPremiumGross:Double,
        val monthlyEstimatedGross:Double,
        val completedSessions:Int
    )

    fun calculate(
        data: JSONArray,
        year: Int,
        month: Int,
        hourlyRate: Double,
        convention: ConventionCatalog.Convention,
        companySlot: Int = 1
    ): Result {
        return if (HoraTrackV2.legacyDisabledFor(HoraTrackV2.Layer.PAYROLL)) {
            fromV2(V2SalaryAdapter.calculateBound(year, month, hourlyRate, convention, companySlot))
        } else {
            calculateLegacy(data, year, month, hourlyRate, convention)
        }
    }

    private fun fromV2(v2: V2SalaryAdapter.Result): Result {
        val nightPremium = v2.premiumsGross
        return Result(
            regularMs = v2.regularMs,
            overtimeTiers = v2.overtimeTiers.map { TierResult(it.label, it.durationMs, it.multiplier) },
            totalWorkedMs = v2.totalWorkedMs,
            workedGross = v2.monthlyEstimatedGross,
            monthlyBaseGross = v2.regularGross,
            overtimeGross = v2.overtimeGross,
            nightMs = v2.nightMs,
            nightPremiumGross = nightPremium,
            saturdayMs = v2.saturdayMs,
            saturdayPremiumGross = 0.0,
            sundayMs = v2.sundayMs,
            sundayPremiumGross = 0.0,
            monthlyEstimatedGross = v2.monthlyEstimatedGross,
            completedSessions = v2.completedSessions
        )
    }

    /** Rollback uniquement : jamais appelé lorsque V2 est actif. */
    private fun calculateLegacy(data:JSONArray, year:Int, month:Int, hourlyRate:Double, convention:ConventionCatalog.Convention):Result {
        V2LegacyPolicy.requireLegacyAllowed(V2LegacyPolicy.Domain.PAYROLL)
        var total = 0L
        var completed = 0
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (item.isNull("exit")) continue
            val entry = item.optLong("entry", -1L)
            val exit = item.optLong("exit", -1L)
            if (entry <= 0L || exit <= entry) continue
            val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entry }
            if (c.get(Calendar.YEAR) != year || c.get(Calendar.MONTH) != month) continue
            total += PointageStore.workedDuration(item, exit)
            completed++
        }
        val gross = total / 3_600_000.0 * hourlyRate.coerceAtLeast(0.0)
        return Result(total, emptyList(), total, gross, gross, 0.0, 0L, 0.0, 0L, 0.0, 0L, 0.0, gross, completed)
    }

    private fun emptyResult(convention:ConventionCatalog.Convention):Result = Result(
        regularMs = 0L,
        overtimeTiers = if (convention.rulesIntegrated) convention.overtimeTiers.map {
            TierResult("Heures sup. +${((it.multiplier - 1.0) * 100).roundToInt()} %", 0L, it.multiplier)
        } else emptyList(),
        totalWorkedMs = 0L,
        workedGross = 0.0,
        monthlyBaseGross = 0.0,
        overtimeGross = 0.0,
        nightMs = 0L,
        nightPremiumGross = 0.0,
        saturdayMs = 0L,
        saturdayPremiumGross = 0.0,
        sundayMs = 0L,
        sundayPremiumGross = 0.0,
        monthlyEstimatedGross = 0.0,
        completedSessions = 0
    )
}
