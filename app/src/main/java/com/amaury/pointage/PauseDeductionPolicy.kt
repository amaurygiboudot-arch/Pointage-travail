package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject

/** Explicit business rule for pause deduction.
 * Recorded pauses are merged first. The configured minimum is only a floor,
 * never an additional pause added on top of recorded time.
 */
internal object PauseDeductionPolicy {
    const val MINIMUM_KEY = "minimumDeductedPauseMinutes"
    const val LEGACY_MINIMUM_KEY = "autoPauseMinutes"

    data class Breakdown(
        val recordedMs: Long,
        val minimumMs: Long,
        val topUpMs: Long,
        val deductedMs: Long
    )

    fun minimumMinutes(item: JSONObject): Int = when {
        item.has(MINIMUM_KEY) -> item.optInt(MINIMUM_KEY, 0)
        else -> item.optInt(LEGACY_MINIMUM_KEY, 0)
    }.coerceIn(0, 240)

    fun breakdown(item: JSONObject, until: Long): Breakdown {
        val entry = item.optLong("entry", -1L)
        if (entry <= 0L) return Breakdown(0L, 0L, 0L, 0L)
        val sessionEnd = if (item.isNull("exit")) until else item.optLong("exit", until)
        if (sessionEnd <= entry) return Breakdown(0L, 0L, 0L, 0L)

        val intervals = mutableListOf<Pair<Long, Long>>()
        val pauses = item.optJSONArray("pauses") ?: JSONArray()
        for (i in 0 until pauses.length()) {
            val pause = pauses.optJSONObject(i) ?: continue
            val rawStart = pause.optLong("start", -1L)
            val rawEnd = if (pause.isNull("end")) until else pause.optLong("end", -1L)
            if (rawStart <= 0L || rawEnd <= rawStart) continue
            val start = rawStart.coerceAtLeast(entry)
            val end = rawEnd.coerceAtMost(sessionEnd)
            if (end > start) intervals += start to end
        }

        var recorded = 0L
        if (intervals.isNotEmpty()) {
            intervals.sortBy { it.first }
            var start = intervals.first().first
            var end = intervals.first().second
            for (i in 1 until intervals.size) {
                val next = intervals[i]
                if (next.first <= end) end = maxOf(end, next.second)
                else {
                    recorded += end - start
                    start = next.first
                    end = next.second
                }
            }
            recorded += end - start
        }

        val rawDuration = sessionEnd - entry
        val minimum = minimumMinutes(item) * 60_000L
        val deducted = maxOf(recorded, minimum).coerceIn(0L, rawDuration)
        val topUp = (deducted - recorded).coerceAtLeast(0L)
        return Breakdown(recorded, minimum.coerceAtMost(rawDuration), topUp, deducted)
    }
}
