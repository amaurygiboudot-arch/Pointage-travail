package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Enregistre plusieurs pauses manuelles sous le même verrou que toutes les
 * autres mutations de pointage afin qu'un widget, une géofence ou une alarme
 * ne puisse pas écraser silencieusement la saisie en cours.
 */
object ManualPauseBatchStore {
    fun addAll(context: Context, ranges: List<Pair<Long, Long>>): Int {
        val valid = ranges.filter { (start, end) -> start > 0L && end > start }
        if (valid.isEmpty()) return 0

        val added = PointageStore.update(context) { data ->
            var count = 0
            valid.forEach { (pauseStart, pauseEnd) ->
                val target = findContainingSession(data, pauseStart, pauseEnd) ?: return@forEach
                val pauses = target.optJSONArray("pauses") ?: JSONArray().also { target.put("pauses", it) }

                var duplicate = false
                for (i in 0 until pauses.length()) {
                    val existing = pauses.optJSONObject(i) ?: continue
                    if (existing.optLong("start", -1L) == pauseStart && existing.optLong("end", -1L) == pauseEnd) {
                        duplicate = true
                        break
                    }
                }
                if (!duplicate) {
                    pauses.put(
                        JSONObject()
                            .put("start", pauseStart)
                            .put("end", pauseEnd)
                            .put("manual", true)
                    )
                    count++
                }
            }
            count
        }

        if (added > 0) {
            PointageWidgetProvider.updateAll(context)
            QuickActionsWidgetProvider.updateAll(context)
            DriveBackupManager.syncCurrentMonthAsync(context)
        }
        return added
    }

    private fun findContainingSession(data: JSONArray, pauseStart: Long, pauseEnd: Long): JSONObject? {
        val now = System.currentTimeMillis()
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || pauseStart < entry) continue

            if (item.isNull("exit")) {
                if (pauseStart <= now) return item
            } else {
                val sessionEnd = item.optLong("exit", -1L)
                if (sessionEnd >= entry && pauseEnd <= sessionEnd) return item
            }
        }
        return null
    }
}
