package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2ConventionRuleStore
import com.amaury.pointage.v2.V2ProfileStore
import com.amaury.pointage.v2.V2RuntimeStore
import com.amaury.pointage.v2.engine.ConventionRuleHistoryV2
import com.amaury.pointage.v2.engine.OvertimeTierV2
import com.amaury.pointage.v2.engine.PayrollEngineV2
import com.amaury.pointage.v2.engine.PayrollRulesV2
import com.amaury.pointage.v2.engine.PayrollWeekV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Passerelle unique entre les écrans Salaire et PayrollEngineV2.
 * Aucune somme salariale V2 ne doit être recalculée dans une autre façade.
 */
object V2SalaryAdapter {
    data class TierDuration(val label: String, val durationMs: Long, val multiplier: Double)
    data class Result(
        val regularMs: Long,
        val overtimeTiers: List<TierDuration>,
        val totalWorkedMs: Long,
        val regularGross: Double,
        val overtimeGross: Double,
        val premiumsGross: Double,
        val monthlyEstimatedGross: Double,
        val nightMs: Long,
        val saturdayMs: Long,
        val sundayMs: Long,
        val completedSessions: Int,
        val warnings: List<String>
    )

    fun calculate(
        context: Context,
        year: Int,
        month: Int,
        hourlyRate: Double,
        convention: ConventionCatalog.Convention,
        companySlot: Int = 1,
        ruleHistory: ConventionRuleHistoryV2? = null
    ): Result {
        require(HoraTrackV2.ENABLED) { "V2SalaryAdapter réservé au moteur V2" }
        val slot = companySlot.coerceIn(1, 2)
        val profile = V2ProfileStore.load(context, slot)
        val history = ruleHistory ?: V2ConventionRuleStore.history(context)
        return calculateCore(
            contract = profile.contract,
            missing = profile.missing,
            sessions = V2RuntimeStore.allSessions(context),
            year = year,
            month = month,
            fallbackRate = hourlyRate,
            convention = convention,
            ruleHistory = history
        )
    }

    /** Variante pour les anciennes façades déjà liées au contexte V2. */
    fun calculateBound(
        year: Int,
        month: Int,
        hourlyRate: Double,
        convention: ConventionCatalog.Convention,
        companySlot: Int = 1,
        ruleHistory: ConventionRuleHistoryV2? = null
    ): Result {
        require(HoraTrackV2.ENABLED) { "V2SalaryAdapter réservé au moteur V2" }
        val profile = V2ProfileStore.loadBound(companySlot.coerceIn(1, 2))
        return calculateCore(
            contract = profile?.contract,
            missing = profile?.missing.orEmpty(),
            sessions = V2RuntimeStore.allSessionsBound(),
            year = year,
            month = month,
            fallbackRate = hourlyRate,
            convention = convention,
            ruleHistory = ruleHistory
        )
    }

    private fun calculateCore(
        contract: ContractV2?,
        missing: List<String>,
        sessions: List<WorkSessionV2>,
        year: Int,
        month: Int,
        fallbackRate: Double,
        convention: ConventionCatalog.Convention,
        ruleHistory: ConventionRuleHistoryV2?
    ): Result {
        val effectiveRate = contract?.grossHourlyRate ?: fallbackRate.takeIf { it > 0.0 }
        if (contract == null || effectiveRate == null) {
            return empty(missing.map { "Fiche Salaire à compléter : $it" })
        }

        val selected = sessions.filter { s ->
            val end = s.realExitMs ?: return@filter false
            val anchor = s.countedEntryMs ?: s.realArrivalMs ?: return@filter false
            val c = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = anchor }
            s.employerId == contract.employerId && end > anchor &&
                c.get(Calendar.YEAR) == year && c.get(Calendar.MONTH) == month
        }
        if (selected.isEmpty()) return empty()

        val warnings = mutableListOf<String>()
        val periodEpochDay = LocalDate.of(year, month + 1, 1).toEpochDay()
        val historicalSnapshot = ruleHistory?.applicable(convention.idcc, periodEpochDay)
        val historicalMode = ruleHistory != null
        val historicalRules = historicalSnapshot?.rules

        val integratedTiers = when {
            historicalMode && historicalRules != null -> historicalRules.overtimeTiers.map {
                ConventionCatalog.OvertimeTier(
                    fromHour = it.fromMinutes / 60.0,
                    toHour = it.toMinutes?.div(60.0),
                    multiplier = it.multiplier
                )
            }
            historicalMode -> emptyList()
            convention.rulesIntegrated -> convention.overtimeTiers
            else -> emptyList()
        }
        if (historicalMode && historicalSnapshot == null) {
            warnings += "Règles conventionnelles historiques : À confirmer pour cette période"
        } else if (!historicalMode && !convention.rulesIntegrated) {
            warnings += "Paliers d'heures supplémentaires non confirmés pour cette convention"
        }

        val contractualRegular = contract.contractualWeeklyMinutes
        val historicalRegular = historicalRules?.weeklyRegularMinutes
        val conventionRegular = integratedTiers.firstOrNull()?.fromHour?.times(60.0)?.roundToInt()
        val regularLimit = historicalRegular ?: contractualRegular ?: conventionRegular
        if (regularLimit == null || regularLimit <= 0) {
            val total = selected.sumOf { HoraTrackV2.time.calculate(it).paidWorkMs }
            return Result(
                regularMs = total,
                overtimeTiers = emptyList(),
                totalWorkedMs = total,
                regularGross = total / 3_600_000.0 * effectiveRate,
                overtimeGross = 0.0,
                premiumsGross = 0.0,
                monthlyEstimatedGross = total / 3_600_000.0 * effectiveRate,
                nightMs = 0L,
                saturdayMs = 0L,
                sundayMs = 0L,
                completedSessions = selected.size,
                warnings = warnings + "Durée hebdomadaire de référence absente : aucune majoration inventée"
            )
        }

        data class WeekStats(var paid: Int = 0, var night: Int = 0, var saturday: Int = 0, var sunday: Int = 0)
        val weeks = linkedMapOf<Pair<Int, Int>, WeekStats>()
        val nightRule = if (historicalMode) null else ConventionNightRules.forIdcc(convention.idcc)
        var nightTotalMs = 0L
        var saturdayTotalMs = 0L
        var sundayTotalMs = 0L

        selected.forEach { s ->
            val start = s.countedEntryMs ?: s.realArrivalMs ?: return@forEach
            val end = s.countedExitMs ?: s.realExitMs ?: return@forEach
            if (end <= start) return@forEach
            val paidMs = HoraTrackV2.time.calculate(s).paidWorkMs
            val paidMinutes = (paidMs / 60_000L).toInt()
            val c = Calendar.getInstance(Locale.FRANCE).apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
                timeInMillis = start
            }
            val stats = weeks.getOrPut(c.getWeekYear() to c.get(Calendar.WEEK_OF_YEAR)) { WeekStats() }
            stats.paid += paidMinutes

            val raw = (end - start).coerceAtLeast(1L)
            nightRule?.let { rule ->
                val rawNight = nightOverlap(start, end, rule.startMinute, rule.endMinute)
                val paidNightMs = ((rawNight.toDouble() / raw.toDouble()) * paidMs).toLong().coerceIn(0L, paidMs)
                val mins = (paidNightMs / 60_000L).toInt()
                stats.night += mins
                nightTotalMs += paidNightMs
            }
            when (c.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SATURDAY -> {
                    stats.saturday += paidMinutes
                    saturdayTotalMs += paidMs
                }
                Calendar.SUNDAY -> {
                    stats.sunday += paidMinutes
                    sundayTotalMs += paidMs
                }
            }
        }

        val payrollWeeks = weeks.values.map {
            PayrollWeekV2(it.paid, it.night, it.saturday, it.sunday)
        }
        val rules = if (historicalRules != null) {
            historicalRules.copy(weeklyRegularMinutes = historicalRules.weeklyRegularMinutes ?: regularLimit)
        } else {
            PayrollRulesV2(
                weeklyRegularMinutes = regularLimit,
                overtimeTiers = integratedTiers.map { tier ->
                    OvertimeTierV2(
                        fromMinutes = (tier.fromHour * 60.0).roundToInt(),
                        toMinutes = tier.toHour?.let { (it * 60.0).roundToInt() },
                        multiplier = tier.multiplier
                    )
                },
                nightMultiplier = nightRule?.premiumMultiplier
            )
        }
        val payroll = PayrollEngineV2.calculate(contract.copy(grossHourlyRate = effectiveRate), payrollWeeks, rules)

        var regularMinutes = 0
        val tierMinutes = LongArray(rules.overtimeTiers.size)
        weeks.values.forEach { stats ->
            val paid = stats.paid
            regularMinutes += minOf(paid, regularLimit)
            rules.overtimeTiers.forEachIndexed { index, tier ->
                val to = tier.toMinutes ?: Int.MAX_VALUE
                tierMinutes[index] += (minOf(paid, to) - maxOf(regularLimit, tier.fromMinutes)).coerceAtLeast(0).toLong()
            }
        }

        val tiers = rules.overtimeTiers.mapIndexed { index, tier ->
            TierDuration(
                label = "Heures sup. +${((tier.multiplier - 1.0) * 100.0).roundToInt()} %",
                durationMs = tierMinutes[index] * 60_000L,
                multiplier = tier.multiplier
            )
        }

        return Result(
            regularMs = regularMinutes.toLong() * 60_000L,
            overtimeTiers = tiers,
            totalWorkedMs = weeks.values.sumOf { it.paid }.toLong() * 60_000L,
            regularGross = payroll.regularGross,
            overtimeGross = payroll.overtimeGross,
            premiumsGross = payroll.premiumsGross,
            monthlyEstimatedGross = payroll.grossEstimate,
            nightMs = nightTotalMs,
            saturdayMs = saturdayTotalMs,
            sundayMs = sundayTotalMs,
            completedSessions = selected.size,
            warnings = warnings + payroll.traces + listOfNotNull(historicalSnapshot?.let {
                "Règles historiques ${it.versionId} — source ${it.sourceId}"
            })
        )
    }

    private fun empty(warnings: List<String> = emptyList()) = Result(
        0L, emptyList(), 0L, 0.0, 0.0, 0.0, 0.0, 0L, 0L, 0L, 0, warnings
    )

    private fun nightOverlap(entry: Long, exit: Long, startMinute: Int, endMinute: Int): Long {
        if (exit <= entry) return 0L
        var total = 0L
        val day = Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = entry
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val last = Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = exit
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        while (day.timeInMillis <= last.timeInMillis) {
            val start = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, startMinute / 60); set(Calendar.MINUTE, startMinute % 60)
            }
            val end = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, endMinute / 60); set(Calendar.MINUTE, endMinute % 60)
                if (endMinute <= startMinute) add(Calendar.DAY_OF_YEAR, 1)
            }
            total += overlap(entry, exit, start.timeInMillis, end.timeInMillis)
            day.add(Calendar.DAY_OF_YEAR, 1)
        }
        return total
    }

    private fun overlap(start: Long, end: Long, rangeStart: Long, rangeEnd: Long): Long {
        val from = maxOf(start, rangeStart)
        val to = minOf(end, rangeEnd)
        return (to - from).coerceAtLeast(0L)
    }
}
