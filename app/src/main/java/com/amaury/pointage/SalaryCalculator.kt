package com.amaury.pointage

import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

object SalaryCalculator {

    data class TierResult(
        val label: String,
        val durationMs: Long,
        val multiplier: Double
    )

    data class Result(
        val regularMs: Long,
        val overtimeTiers: List<TierResult>,
        val totalWorkedMs: Long,
        val workedGross: Double,
        val monthlyBaseGross: Double,
        val overtimeGross: Double,
        val monthlyEstimatedGross: Double,
        val completedSessions: Int
    )

    private data class Session(val entry: Long, val exit: Long)
    private data class WeekKey(val year: Int, val week: Int)

    fun calculate(
        data: JSONArray,
        year: Int,
        month: Int,
        hourlyRate: Double,
        convention: ConventionCatalog.Convention
    ): Result {
        val sessions = mutableListOf<Session>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (item.isNull("exit")) continue
            val entry = item.optLong("entry", -1L)
            val exit = item.optLong("exit", -1L)
            if (entry > 0L && exit > entry) sessions.add(Session(entry, exit))
        }
        sessions.sortBy { it.entry }

        val sessionsByWeek = linkedMapOf<WeekKey, MutableList<Session>>()
        sessions.forEach { session ->
            val cal = Calendar.getInstance(Locale.FRANCE).apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
                timeInMillis = session.entry
            }
            val key = WeekKey(cal.getWeekYear(), cal.get(Calendar.WEEK_OF_YEAR))
            sessionsByWeek.getOrPut(key) { mutableListOf() }.add(session)
        }

        val hourMs = 60L * 60L * 1000L
        val normalLimit = 35L * hourMs
        var regularMs = 0L
        var completedSessions = 0
        val overtimeByTier = LongArray(convention.overtimeTiers.size)

        sessionsByWeek.values.forEach { weekSessions ->
            var cumulative = 0L
            weekSessions.sortedBy { it.entry }.forEach { session ->
                val duration = session.exit - session.entry
                val startCum = cumulative
                val endCum = cumulative + duration
                val entryCal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = session.entry }

                if (entryCal.get(Calendar.YEAR) == year && entryCal.get(Calendar.MONTH) == month) {
                    regularMs += overlap(startCum, endCum, 0L, normalLimit)
                    convention.overtimeTiers.forEachIndexed { index, tier ->
                        val from = (tier.fromHour * hourMs).toLong()
                        val to = tier.toHour?.let { (it * hourMs).toLong() } ?: Long.MAX_VALUE
                        overtimeByTier[index] += overlap(startCum, endCum, from, to)
                    }
                    completedSessions++
                }
                cumulative = endCum
            }
        }

        val tierResults = convention.overtimeTiers.mapIndexed { index, tier ->
            val percent = ((tier.multiplier - 1.0) * 100.0).toInt()
            TierResult("Heures sup. +$percent %", overtimeByTier[index], tier.multiplier)
        }

        val regularHours = regularMs / hourMs.toDouble()
        val overtimeGross = tierResults.sumOf {
            (it.durationMs / hourMs.toDouble()) * hourlyRate * it.multiplier
        }
        val totalOvertimeMs = tierResults.sumOf { it.durationMs }
        val workedGross = regularHours * hourlyRate + overtimeGross
        val monthlyBaseGross = hourlyRate * 151.67
        val monthlyEstimatedGross = monthlyBaseGross + overtimeGross

        return Result(
            regularMs = regularMs,
            overtimeTiers = tierResults,
            totalWorkedMs = regularMs + totalOvertimeMs,
            workedGross = workedGross,
            monthlyBaseGross = monthlyBaseGross,
            overtimeGross = overtimeGross,
            monthlyEstimatedGross = monthlyEstimatedGross,
            completedSessions = completedSessions
        )
    }

    private fun overlap(start: Long, end: Long, rangeStart: Long, rangeEnd: Long): Long {
        val from = maxOf(start, rangeStart)
        val to = minOf(end, rangeEnd)
        return (to - from).coerceAtLeast(0L)
    }
}
