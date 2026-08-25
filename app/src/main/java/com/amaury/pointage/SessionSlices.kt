package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/** Utilities for attributing a closed work session to calendar ranges without
 * losing or double-counting pauses that cross the same boundaries. */
object SessionSlices {
    fun clip(item: JSONObject, rangeStart: Long, rangeEnd: Long): JSONObject? {
        if (rangeEnd <= rangeStart || item.isNull("exit")) return null
        val entry = item.optLong("entry", -1L)
        val exit = item.optLong("exit", -1L)
        if (entry <= 0L || exit <= entry) return null
        val start = maxOf(entry, rangeStart)
        val end = minOf(exit, rangeEnd)
        if (end <= start) return null

        val fullRecorded = mergedRecordedPauses(item, entry, exit)
        val recordedDuration = fullRecorded.sumOf { (s, e) -> e - s }
        val rawDuration = exit - entry
        val automaticFloor = (item.optInt("autoPauseMinutes", 0).coerceIn(0, 240) * 60_000L)
            .coerceAtMost(rawDuration)
        val extraAutomatic = (automaticFloor - recordedDuration)
            .coerceIn(0L, (rawDuration - recordedDuration).coerceAtLeast(0L))

        val copy = JSONObject(item.toString())
        copy.put("entry", start)
        copy.put("exit", end)
        // The original automatic floor belongs to the whole session, not to every
        // calendar slice. It is materialized below as non-overlapping allocated
        // pause intervals, so PointageStore can use its normal merge logic.
        copy.put("autoPauseMinutes", 0)

        val clippedPauses = JSONArray()
        val pauses = item.optJSONArray("pauses")
        if (pauses != null) {
            for (i in 0 until pauses.length()) {
                val pause = pauses.optJSONObject(i) ?: continue
                val rawStart = pause.optLong("start", -1L)
                val rawEnd = if (pause.isNull("end")) exit else pause.optLong("end", -1L)
                if (rawStart <= 0L || rawEnd <= rawStart) continue
                val pauseStart = maxOf(rawStart, start)
                val pauseEnd = minOf(rawEnd, end)
                if (pauseEnd <= pauseStart) continue
                val clipped = JSONObject(pause.toString())
                    .put("start", pauseStart)
                    .put("end", pauseEnd)
                clippedPauses.put(clipped)
            }
        }

        if (extraAutomatic > 0L) {
            val explicitWorkedTotal = (rawDuration - recordedDuration).coerceAtLeast(0L)
            if (explicitWorkedTotal > 0L) {
                val workedBeforeStart = explicitWorkedBetween(entry, start, fullRecorded)
                val workedThroughEnd = explicitWorkedBetween(entry, end, fullRecorded)
                val allocatedBefore = extraAutomatic * workedBeforeStart / explicitWorkedTotal
                val allocatedThrough = extraAutomatic * workedThroughEnd / explicitWorkedTotal
                var remaining = (allocatedThrough - allocatedBefore).coerceAtLeast(0L)

                if (remaining > 0L) {
                    val clippedRecorded = clipIntervals(fullRecorded, start, end)
                    for ((gapStart, gapEnd) in freeGaps(start, end, clippedRecorded)) {
                        if (remaining <= 0L) break
                        val duration = minOf(remaining, gapEnd - gapStart)
                        if (duration > 0L) {
                            clippedPauses.put(
                                JSONObject()
                                    .put("start", gapStart)
                                    .put("end", gapStart + duration)
                                    .put("automatic", true)
                                    .put("allocatedAutomatic", true)
                            )
                            remaining -= duration
                        }
                    }
                }
            }
        }

        copy.put("pauses", clippedPauses)
        return copy
    }

    fun splitByDay(item: JSONObject): List<JSONObject> {
        if (item.isNull("exit")) return emptyList()
        val entry = item.optLong("entry", -1L)
        val exit = item.optLong("exit", -1L)
        if (entry <= 0L || exit <= entry) return emptyList()

        val result = mutableListOf<JSONObject>()
        var cursor = entry
        while (cursor < exit) {
            val nextDay = Calendar.getInstance(Locale.FRANCE).apply {
                timeInMillis = cursor
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, 1)
            }.timeInMillis
            val segmentEnd = minOf(exit, nextDay)
            clip(item, cursor, segmentEnd)?.let(result::add)
            cursor = segmentEnd
        }
        return result
    }

    private fun mergedRecordedPauses(item: JSONObject, entry: Long, exit: Long): List<Pair<Long, Long>> {
        val pauses = item.optJSONArray("pauses") ?: return emptyList()
        val intervals = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until pauses.length()) {
            val pause = pauses.optJSONObject(i) ?: continue
            val rawStart = pause.optLong("start", -1L)
            val rawEnd = if (pause.isNull("end")) exit else pause.optLong("end", -1L)
            if (rawStart <= 0L || rawEnd <= rawStart) continue
            val s = maxOf(rawStart, entry)
            val e = minOf(rawEnd, exit)
            if (e > s) intervals += s to e
        }
        if (intervals.isEmpty()) return emptyList()
        intervals.sortBy { it.first }
        val merged = mutableListOf<Pair<Long, Long>>()
        var currentStart = intervals.first().first
        var currentEnd = intervals.first().second
        for (i in 1 until intervals.size) {
            val (s, e) = intervals[i]
            if (s <= currentEnd) currentEnd = maxOf(currentEnd, e)
            else {
                merged += currentStart to currentEnd
                currentStart = s
                currentEnd = e
            }
        }
        merged += currentStart to currentEnd
        return merged
    }

    private fun explicitWorkedBetween(
        sessionEntry: Long,
        bound: Long,
        recorded: List<Pair<Long, Long>>
    ): Long {
        if (bound <= sessionEntry) return 0L
        val gross = bound - sessionEntry
        val paused = recorded.sumOf { (s, e) ->
            val from = maxOf(s, sessionEntry)
            val to = minOf(e, bound)
            (to - from).coerceAtLeast(0L)
        }
        return (gross - paused).coerceAtLeast(0L)
    }

    private fun clipIntervals(
        intervals: List<Pair<Long, Long>>,
        start: Long,
        end: Long
    ): List<Pair<Long, Long>> = intervals.mapNotNull { (s, e) ->
        val clippedStart = maxOf(s, start)
        val clippedEnd = minOf(e, end)
        if (clippedEnd > clippedStart) clippedStart to clippedEnd else null
    }

    private fun freeGaps(
        start: Long,
        end: Long,
        blocked: List<Pair<Long, Long>>
    ): List<Pair<Long, Long>> {
        val gaps = mutableListOf<Pair<Long, Long>>()
        var cursor = start
        blocked.sortedBy { it.first }.forEach { (s, e) ->
            if (s > cursor) gaps += cursor to minOf(s, end)
            cursor = maxOf(cursor, e)
        }
        if (cursor < end) gaps += cursor to end
        return gaps.filter { it.second > it.first }
    }

    fun startOfDay(time: Long): Long = Calendar.getInstance(Locale.FRANCE).apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun monthStart(year: Int, month: Int): Long = Calendar.getInstance(Locale.FRANCE).apply {
        clear()
        set(year, month, 1, 0, 0, 0)
    }.timeInMillis

    fun monthEnd(year: Int, month: Int): Long = Calendar.getInstance(Locale.FRANCE).apply {
        clear()
        set(year, month, 1, 0, 0, 0)
        add(Calendar.MONTH, 1)
    }.timeInMillis
}
