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

        val copy = JSONObject(item.toString())
        copy.put("entry", start)
        copy.put("exit", end)
        val clippedPauses = JSONArray()
        val pauses = item.optJSONArray("pauses")
        if (pauses != null) {
            for (i in 0 until pauses.length()) {
                val pause = pauses.optJSONObject(i) ?: continue
                val rawStart = pause.optLong("start", -1L)
                val rawEnd = if (pause.isNull("end")) end else pause.optLong("end", -1L)
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
