package com.amaury.pointage

import org.json.JSONObject

/** Calculs purs de temps de travail, sans dépendance Android. */
object WorkTimeMath {
    fun pauseDuration(item: JSONObject, until: Long = System.currentTimeMillis()): Long {
        val entry = item.optLong("entry", -1L)
        if (entry <= 0L) return 0L
        val sessionEnd = if (item.isNull("exit")) until else item.optLong("exit", until)
        if (sessionEnd <= entry) return 0L

        val rawDuration = sessionEnd - entry
        val basePause = item.optInt("autoPauseMinutes", 0).coerceIn(0, 240) * 60_000L
        val pauses = item.optJSONArray("pauses")
        val intervals = mutableListOf<Pair<Long, Long>>()

        if (pauses != null) {
            for (i in 0 until pauses.length()) {
                val pause = pauses.optJSONObject(i) ?: continue
                // Une pause automatique matérialise le forfait déjà compté par autoPauseMinutes.
                if (basePause > 0L && pause.optBoolean("automatic", false)) continue
                val start = pause.optLong("start", -1L)
                val end = if (pause.isNull("end")) until else pause.optLong("end", -1L)
                if (start <= 0L || end <= start) continue
                val boundedStart = start.coerceAtLeast(entry)
                val boundedEnd = end.coerceAtMost(sessionEnd)
                if (boundedEnd > boundedStart) intervals += boundedStart to boundedEnd
            }
        }

        var additionalPause = 0L
        if (intervals.isNotEmpty()) {
            intervals.sortBy { it.first }
            var currentStart = intervals.first().first
            var currentEnd = intervals.first().second
            for (i in 1 until intervals.size) {
                val (start, end) = intervals[i]
                if (start <= currentEnd) {
                    currentEnd = maxOf(currentEnd, end)
                } else {
                    additionalPause += currentEnd - currentStart
                    currentStart = start
                    currentEnd = end
                }
            }
            additionalPause += currentEnd - currentStart
        }

        return (basePause + additionalPause).coerceIn(0L, rawDuration)
    }

    fun workedDuration(item: JSONObject, until: Long = System.currentTimeMillis()): Long {
        val entry = item.optLong("entry", -1L)
        if (entry <= 0L) return 0L
        val end = if (item.isNull("exit")) until else item.optLong("exit", until)
        if (end <= entry) return 0L
        return ((end - entry) - pauseDuration(item, end)).coerceAtLeast(0L)
    }
}
