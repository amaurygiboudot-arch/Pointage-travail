package com.amaury.pointage

import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

object SalaryCalculator {

    data class Result(
        val regularMs: Long,
        val overtime25Ms: Long,
        val overtime50Ms: Long,
        val totalWorkedMs: Long,
        val workedGross: Double,
        val monthlyBaseGross: Double,
        val overtimeGross: Double,
        val monthlyEstimatedGross: Double,
        val completedSessions: Int
    )

    private data class Session(
        val entry: Long,
        val exit: Long
    )

    private data class WeekKey(val year: Int, val week: Int)

    fun calculate(
        data: JSONArray,
        year: Int,
        month: Int,
        hourlyRate: Double
    ): Result {
        val sessions = mutableListOf<Session>()

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (item.isNull("exit")) continue
            val entry = item.optLong("entry", -1L)
            val exit = item.optLong("exit", -1L)
            if (entry <= 0L || exit <= entry) continue
            sessions.add(Session(entry, exit))
        }

        sessions.sortBy { it.entry }

        val sessionsByWeek = linkedMapOf<WeekKey, MutableList<Session>>()
        sessions.forEach { session ->
            val cal = Calendar.getInstance(Locale.FRANCE).apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
                timeInMillis = session.entry
            }
            val key = WeekKey(
                cal.getWeekYear(),
                cal.get(Calendar.WEEK_OF_YEAR)
            )
            sessionsByWeek.getOrPut(key) { mutableListOf() }.add(session)
        }

        val normalLimit = 35L * 60L * 60L * 1000L
        val firstOvertimeLimit = 43L * 60L * 60L * 1000L

        var regularMs = 0L
        var overtime25Ms = 0L
        var overtime50Ms = 0L
        var completedSessions = 0

        sessionsByWeek.values.forEach { weekSessions ->
            var cumulative = 0L

            weekSessions.sortedBy { it.entry }.forEach { session ->
                val duration = session.exit - session.entry
                val startCum = cumulative
                val endCum = cumulative + duration

                val regularPart = overlap(startCum, endCum, 0L, normalLimit)
                val overtime25Part = overlap(startCum, endCum, normalLimit, firstOvertimeLimit)
                val overtime50Part = (duration - regularPart - overtime25Part).coerceAtLeast(0L)

                val entryCal = Calendar.getInstance(Locale.FRANCE).apply {
                    timeInMillis = session.entry
                }

                if (entryCal.get(Calendar.YEAR) == year && entryCal.get(Calendar.MONTH) == month) {
                    regularMs += regularPart
                    overtime25Ms += overtime25Part
                    overtime50Ms += overtime50Part
                    completedSessions++
                }

                cumulative = endCum
            }
        }

        val hourMs = 60.0 * 60.0 * 1000.0
        val regularHours = regularMs / hourMs
        val overtime25Hours = overtime25Ms / hourMs
        val overtime50Hours = overtime50Ms / hourMs

        val workedGross =
            regularHours * hourlyRate +
            overtime25Hours * hourlyRate * 1.25 +
            overtime50Hours * hourlyRate * 1.50

        val overtimeGross =
            overtime25Hours * hourlyRate * 1.25 +
            overtime50Hours * hourlyRate * 1.50

        val monthlyBaseGross = hourlyRate * 151.67
        val monthlyEstimatedGross = monthlyBaseGross + overtimeGross

        return Result(
            regularMs = regularMs,
            overtime25Ms = overtime25Ms,
            overtime50Ms = overtime50Ms,
            totalWorkedMs = regularMs + overtime25Ms + overtime50Ms,
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
