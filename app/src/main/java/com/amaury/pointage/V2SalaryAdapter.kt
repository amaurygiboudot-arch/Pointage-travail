package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.OvertimeTierV2
import com.amaury.pointage.v2.engine.PayrollEngineV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.amaury.pointage.v2.engine.PayrollWeekV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import java.util.Calendar
import java.util.Locale

/** Adaptateur V2 pour conserver l'interface salaire existante sans SalaryCalculator. */
object V2SalaryAdapter {
    data class TierDuration(val label: String, val durationMs: Long)
    data class Result(
        val regularMs: Long,
        val overtimeTiers: List<TierDuration>,
        val totalWorkedMs: Long,
        val overtimeGross: Double,
        val monthlyEstimatedGross: Double,
        val completedSessions: Int,
        val warnings: List<String>
    )

    fun calculate(
        context: Context,
        year: Int,
        month: Int,
        hourlyRate: Double,
        convention: ConventionCatalog.Convention
    ): Result {
        require(HoraTrackV2.ENABLED) { "V2SalaryAdapter réservé au moteur V2" }
        require(hourlyRate > 0.0)

        val sessions = V2RuntimeStore.allSessions(context)
            .filter { s ->
                val end = s.realExitMs ?: return@filter false
                val anchor = s.countedEntryMs ?: s.realArrivalMs ?: return@filter false
                val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }
                end > anchor && c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month
            }

        if (sessions.isEmpty()) {
            return Result(0L, emptyList(), 0L, 0.0, 0.0, 0, emptyList())
        }

        val warnings = mutableListOf<String>()
        val integratedTiers = if (convention.rulesIntegrated) convention.overtimeTiers else emptyList()
        if (!convention.rulesIntegrated) {
            warnings += "Paliers d'heures supplémentaires non confirmés pour cette convention"
        }

        val regularLimitMinutes = integratedTiers.firstOrNull()?.fromHour?.times(60.0)?.toInt()
        if (regularLimitMinutes == null || regularLimitMinutes <= 0) {
            val total = sessions.sumOf { HoraTrackV2.time.calculate(it).paidWorkMs }
            return Result(
                regularMs = total,
                overtimeTiers = emptyList(),
                totalWorkedMs = total,
                overtimeGross = 0.0,
                monthlyEstimatedGross = total / 3_600_000.0 * hourlyRate,
                completedSessions = sessions.size,
                warnings = warnings + "Durée hebdomadaire de référence manquante : aucune majoration appliquée"
            )
        }

        val grouped = linkedMapOf<Pair<Int, Int>, Int>()
        sessions.forEach { s ->
            val anchor = s.countedEntryMs ?: s.realArrivalMs ?: return@forEach
            val c = Calendar.getInstance(Locale.FRANCE).apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
                timeInMillis = anchor
            }
            val key = c.getWeekYear() to c.get(Calendar.WEEK_OF_YEAR)
            val minutes = (HoraTrackV2.time.calculate(s).paidWorkMs / 60_000L).toInt()
            grouped[key] = (grouped[key] ?: 0) + minutes
        }

        val payrollWeeks = grouped.values.map { PayrollWeekV2(paidMinutes = it) }
        val rules = PayrollRulesV2(
            weeklyRegularMinutes = regularLimitMinutes,
            overtimeTiers = integratedTiers.map { tier ->
                OvertimeTierV2(
                    fromMinutes = (tier.fromHour * 60.0).toInt(),
                    toMinutes = tier.toHour?.let { (it * 60.0).toInt() },
                    multiplier = tier.multiplier
                )
            }
        )
        val contract = ContractV2(
            id = "ui-v2",
            employerId = "principal",
            type = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = regularLimitMinutes,
            grossHourlyRate = hourlyRate,
            hireDateEpochDay = null
        )
        val payroll = PayrollEngineV2.calculate(contract, payrollWeeks, rules)

        var regularMinutes = 0
        val tierMinutes = LongArray(integratedTiers.size)
        grouped.values.forEach { paid ->
            regularMinutes += minOf(paid, regularLimitMinutes)
            integratedTiers.forEachIndexed { index, tier ->
                val from = (tier.fromHour * 60.0).toInt()
                val to = tier.toHour?.let { (it * 60.0).toInt() } ?: Int.MAX_VALUE
                tierMinutes[index] += (minOf(paid, to) - maxOf(from, regularLimitMinutes)).coerceAtLeast(0).toLong()
            }
        }

        val tiers = integratedTiers.mapIndexed { index, tier ->
            TierDuration(
                label = "Heures sup. +${((tier.multiplier - 1.0) * 100.0).toInt()} %",
                durationMs = tierMinutes[index] * 60_000L
            )
        }
        val totalMs = grouped.values.sum().toLong() * 60_000L

        return Result(
            regularMs = regularMinutes.toLong() * 60_000L,
            overtimeTiers = tiers,
            totalWorkedMs = totalMs,
            overtimeGross = payroll.overtimeGross,
            monthlyEstimatedGross = payroll.grossEstimate,
            completedSessions = sessions.size,
            warnings = warnings + payroll.traces
        )
    }
}
