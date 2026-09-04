package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stockage V2 réservé aux pauses prévues.
 *
 * Une pause présente ici n'est jamais injectée dans une WorkSessionV2 et ne peut donc pas
 * diminuer le temps payé. Elle ne rejoint les pauses réelles qu'après confirmation explicite
 * depuis l'interface.
 */
object PlannedPauseStoreV2 {
    const val PREFS_NAME = "horatrack_v2_planned_pauses"
    private const val KEY_RANGES = "ranges"

    fun forDay(context: Context, dayStart: Long, dayEnd: Long): List<Pair<Long, Long>> {
        if (dayStart <= 0L || dayEnd <= dayStart) return emptyList()
        return readAll(context)
            .filter { (start, end) -> start in dayStart until dayEnd && end <= dayEnd }
            .sortedBy { it.first }
    }

    /**
     * Remplace uniquement les pauses prévues du jour demandé. Les autres jours restent intacts.
     */
    fun replaceDay(
        context: Context,
        dayStart: Long,
        dayEnd: Long,
        ranges: List<Pair<Long, Long>>
    ): Boolean {
        if (dayStart <= 0L || dayEnd <= dayStart) return false
        val clean = normalizeRanges(ranges)
        if (clean.any { (start, end) -> start !in dayStart until dayEnd || end > dayEnd }) return false

        val kept = readAll(context).filterNot { (start, _) -> start in dayStart until dayEnd }
        val merged = normalizeRanges(kept + clean)
        val editor = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()

        if (merged.isEmpty()) {
            editor.remove(KEY_RANGES)
        } else {
            editor.putString(KEY_RANGES, encode(merged))
        }
        return editor.commit()
    }

    internal fun normalizeRanges(ranges: List<Pair<Long, Long>>): List<Pair<Long, Long>> =
        ranges
            .filter { (start, end) -> start > 0L && end > start }
            .distinct()
            .sortedBy { it.first }

    private fun readAll(context: Context): List<Pair<Long, Long>> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_RANGES, "[]")
            .orEmpty()
        val array = runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val start = item.optLong("start", -1L)
                val end = item.optLong("end", -1L)
                if (start > 0L && end > start) add(start to end)
            }
        }.distinct().sortedBy { it.first }
    }

    private fun encode(ranges: List<Pair<Long, Long>>): String = JSONArray().apply {
        ranges.forEach { (start, end) ->
            put(JSONObject().put("start", start).put("end", end))
        }
    }.toString()
}
