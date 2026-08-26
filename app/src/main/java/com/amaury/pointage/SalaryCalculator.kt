package com.amaury.pointage

import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

object SalaryCalculator {

    data class TierResult(val label: String, val durationMs: Long, val multiplier: Double)

    data class Result(
        val regularMs: Long,
        val overtimeTiers: List<TierResult>,
        val totalWorkedMs: Long,
        val workedGross: Double,
        val monthlyBaseGross: Double,
        val overtimeGross: Double,
        val nightMs: Long,
        val nightPremiumGross: Double,
        val saturdayMs: Long,
        val saturdayPremiumGross: Double,
        val sundayMs: Long,
        val sundayPremiumGross: Double,
        val monthlyEstimatedGross: Double,
        val completedSessions: Int
    )

    private data class Session(
        val id: Int,
        val entry: Long,
        val exit: Long,
        val workedDuration: Long
    )

    /** Segment calendaire ne traversant jamais minuit. */
    private data class Segment(
        val sessionId: Int,
        val start: Long,
        val end: Long,
        val paidDuration: Long
    )

    private data class WeekKey(val year: Int, val week: Int)

    /**
     * Les valeurs par défaut conservent le comportement historique de HoraTrack,
     * mais elles sont maintenant explicites et peuvent être remplacées par l'appelant.
     * Les règles conventionnelles/accord d'entreprise restent prioritaires lorsqu'elles
     * sont connues par l'interface appelante.
     */
    fun calculate(
        data: JSONArray,
        year: Int,
        month: Int,
        hourlyRate: Double,
        convention: ConventionCatalog.Convention,
        nightPremiumRate: Double = 0.25,
        saturdayPremiumRate: Double = 0.25,
        sundayPremiumRate: Double = 0.50,
        monthlyBaseHours: Double = 151.67
    ): Result {
        val sessions = mutableListOf<Session>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            if (item.isNull("exit")) continue
            val entry = item.optLong("entry", -1L)
            val exit = item.optLong("exit", -1L)
            if (entry > 0L && exit > entry) {
                val paid = PointageStore.workedDuration(item, exit).coerceIn(0L, exit - entry)
                sessions += Session(i, entry, exit, paid)
            }
        }
        sessions.sortBy { it.entry }

        val segments = sessions.flatMap(::splitAtMidnights).sortedBy { it.start }
        val weeks = linkedMapOf<WeekKey, MutableList<Segment>>()
        segments.forEach { segment ->
            val c = calendarAt(segment.start)
            weeks.getOrPut(WeekKey(c.getWeekYear(), c.get(Calendar.WEEK_OF_YEAR))) { mutableListOf() }
                .add(segment)
        }

        val hourMs = 3_600_000L
        val normalLimit = 35L * hourMs
        var regularMs = 0L
        val completedSessionIds = linkedSetOf<Int>()
        val overtime = LongArray(convention.overtimeTiers.size)
        var nightMs = 0L
        var saturdayMs = 0L
        var sundayMs = 0L
        var saturdayPremium = 0.0
        var sundayPremium = 0.0
        var nightPremium = 0.0

        weeks.values.forEach { weekSegments ->
            var cumulative = 0L
            weekSegments.sortedBy { it.start }.forEach { segment ->
                val duration = segment.paidDuration
                if (duration <= 0L) return@forEach

                val startCum = cumulative
                val endCum = cumulative + duration
                val cal = calendarAt(segment.start)
                val inMonth = cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month

                if (inMonth) {
                    completedSessionIds += segment.sessionId
                    val regularPart = overlap(startCum, endCum, 0L, normalLimit)
                    regularMs += regularPart
                    var segmentValue = (regularPart / hourMs.toDouble()) * hourlyRate

                    convention.overtimeTiers.forEachIndexed { idx, tier ->
                        val from = (tier.fromHour * hourMs).toLong()
                        val to = tier.toHour?.let { (it * hourMs).toLong() } ?: Long.MAX_VALUE
                        val d = overlap(startCum, endCum, from, to)
                        overtime[idx] += d
                        segmentValue += (d / hourMs.toDouble()) * hourlyRate * tier.multiplier
                    }

                    val dayPremiumRate = when (cal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.SATURDAY -> saturdayPremiumRate.coerceAtLeast(0.0)
                        Calendar.SUNDAY -> sundayPremiumRate.coerceAtLeast(0.0)
                        else -> 0.0
                    }
                    when (cal.get(Calendar.DAY_OF_WEEK)) {
                        Calendar.SATURDAY -> {
                            saturdayMs += duration
                            saturdayPremium += segmentValue * saturdayPremiumRate.coerceAtLeast(0.0)
                        }
                        Calendar.SUNDAY -> {
                            sundayMs += duration
                            sundayPremium += segmentValue * sundayPremiumRate.coerceAtLeast(0.0)
                        }
                    }

                    // Le segment ne traverse pas minuit. On calcule l'overlap réel de nuit,
                    // puis on le rapporte au temps payé de ce segment afin qu'une pause de base
                    // non horodatée ne puisse créer davantage d'heures majorées que d'heures payées.
                    val raw = segment.end - segment.start
                    val rawNight = nightOverlap(segment.start, segment.end, 21 * 60, 6 * 60)
                    val paidNight = if (raw <= 0L) 0L else
                        ((rawNight.toDouble() / raw.toDouble()) * duration).toLong().coerceIn(0L, duration)
                    nightMs += paidNight
                    if (duration > 0L && paidNight > 0L) {
                        val nightShare = paidNight / duration.toDouble()
                        nightPremium += segmentValue * (1.0 + dayPremiumRate) * nightShare * nightPremiumRate.coerceAtLeast(0.0)
                    }
                }
                cumulative = endCum
            }
        }

        val tiers = convention.overtimeTiers.mapIndexed { idx, tier ->
            TierResult(
                "Heures sup. +${((tier.multiplier - 1.0) * 100).toInt()} %",
                overtime[idx],
                tier.multiplier
            )
        }
        val overtimeGross = tiers.sumOf { (it.durationMs / hourMs.toDouble()) * hourlyRate * it.multiplier }
        val totalOvertime = tiers.sumOf { it.durationMs }
        val workedGross =
            (regularMs / hourMs.toDouble()) * hourlyRate + overtimeGross + saturdayPremium + sundayPremium + nightPremium
        val monthlyBaseGross = hourlyRate * monthlyBaseHours.coerceAtLeast(0.0)
        val monthlyEstimatedGross = monthlyBaseGross + overtimeGross + saturdayPremium + sundayPremium + nightPremium

        return Result(
            regularMs,
            tiers,
            regularMs + totalOvertime,
            workedGross,
            monthlyBaseGross,
            overtimeGross,
            nightMs,
            nightPremium,
            saturdayMs,
            saturdayPremium,
            sundayMs,
            sundayPremium,
            monthlyEstimatedGross,
            completedSessionIds.size
        )
    }

    /**
     * Découpe une session à chaque minuit local. Le temps réellement payé est réparti
     * proportionnellement aux durées brutes. Cela permet de traiter correctement les
     * changements de jour/semaine/mois, même lorsque la pause forfaitaire n'a pas d'heure.
     */
    private fun splitAtMidnights(session: Session): List<Segment> {
        val rawTotal = session.exit - session.entry
        if (rawTotal <= 0L || session.workedDuration <= 0L) return emptyList()

        val bounds = mutableListOf<Long>()
        var cursor = session.entry
        while (cursor < session.exit) {
            bounds += cursor
            val nextMidnight = calendarAt(cursor).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            cursor = minOf(session.exit, nextMidnight.coerceAtLeast(cursor + 1L))
        }
        bounds += session.exit

        val result = mutableListOf<Segment>()
        var allocated = 0L
        for (i in 0 until bounds.size - 1) {
            val start = bounds[i]
            val end = bounds[i + 1]
            val rawPart = (end - start).coerceAtLeast(0L)
            val paid = if (i == bounds.size - 2) {
                (session.workedDuration - allocated).coerceAtLeast(0L)
            } else {
                ((rawPart.toDouble() / rawTotal.toDouble()) * session.workedDuration)
                    .toLong()
                    .coerceIn(0L, rawPart)
            }
            allocated += paid
            result += Segment(session.id, start, end, paid.coerceAtMost(rawPart))
        }
        return result
    }

    private fun calendarAt(time: Long): Calendar = Calendar.getInstance(Locale.FRANCE).apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        timeInMillis = time
    }

    private fun nightOverlap(entry: Long, exit: Long, startMinute: Int, endMinute: Int): Long {
        if (exit <= entry) return 0L
        var total = 0L
        val day = calendarAt(entry).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val last = calendarAt(exit).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        while (day.timeInMillis <= last.timeInMillis) {
            val start = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, startMinute / 60)
                set(Calendar.MINUTE, startMinute % 60)
            }
            val end = (day.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, endMinute / 60)
                set(Calendar.MINUTE, endMinute % 60)
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
