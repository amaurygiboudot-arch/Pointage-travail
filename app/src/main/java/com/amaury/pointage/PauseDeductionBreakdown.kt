package com.amaury.pointage

import org.json.JSONObject

/**
 * Explique la règle métier de pause utilisée par PointageStore :
 * autoPauseMinutes est un minimum forfaitaire de pause déduite, pas une pause
 * supplémentaire. La durée effectivement déduite est donc le maximum entre
 * les pauses réellement enregistrées et ce minimum.
 */
object PauseDeductionBreakdown {
    data class Result(
        val recordedMs: Long,
        val minimumDeductedMs: Long,
        val forfaitAdjustmentMs: Long,
        val deductedMs: Long
    )

    fun forSession(item: JSONObject, until: Long = System.currentTimeMillis()): Result {
        val entry = item.optLong("entry", -1L)
        if (entry <= 0L) return Result(0L, 0L, 0L, 0L)
        val sessionEnd = if (item.isNull("exit")) until else item.optLong("exit", until)
        if (sessionEnd <= entry) return Result(0L, 0L, 0L, 0L)

        val intervals = mutableListOf<Pair<Long, Long>>()
        val pauses = item.optJSONArray("pauses")
        if (pauses != null) {
            for (i in 0 until pauses.length()) {
                val pause = pauses.optJSONObject(i) ?: continue
                val rawStart = pause.optLong("start", -1L)
                val rawEnd = if (pause.isNull("end")) until else pause.optLong("end", -1L)
                if (rawStart <= 0L || rawEnd <= rawStart) continue
                val start = rawStart.coerceAtLeast(entry)
                val end = rawEnd.coerceAtMost(sessionEnd)
                if (end > start) intervals += start to end
            }
        }

        var recorded = 0L
        if (intervals.isNotEmpty()) {
            intervals.sortBy { it.first }
            var currentStart = intervals.first().first
            var currentEnd = intervals.first().second
            for (i in 1 until intervals.size) {
                val (start, end) = intervals[i]
                if (start <= currentEnd) currentEnd = maxOf(currentEnd, end)
                else {
                    recorded += currentEnd - currentStart
                    currentStart = start
                    currentEnd = end
                }
            }
            recorded += currentEnd - currentStart
        }

        val rawDuration = sessionEnd - entry
        val minimum = (item.optInt("autoPauseMinutes", 0).coerceIn(0, 240) * 60_000L)
            .coerceAtMost(rawDuration)
        val deducted = maxOf(recorded, minimum).coerceAtMost(rawDuration)
        return Result(
            recordedMs = recorded,
            minimumDeductedMs = minimum,
            forfaitAdjustmentMs = (minimum - recorded).coerceAtLeast(0L),
            deductedMs = deducted
        )
    }
}
