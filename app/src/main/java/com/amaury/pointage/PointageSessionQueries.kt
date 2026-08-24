package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Read-only selectors shared by UI surfaces that need a trustworthy session. */
object PointageSessionQueries {
    fun latestValidSession(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): JSONObject? = latestValidSession(PointageStore.load(context), now)

    internal fun latestValidSession(data: JSONArray, now: Long): JSONObject? {
        var oldestValidOpen: JSONObject? = null
        var oldestValidOpenEntry = Long.MAX_VALUE
        var latestCompleted: JSONObject? = null
        var latestCompletedEntry = Long.MIN_VALUE

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || entry > now) continue

            // Legitimate sessions always persist an explicit exit key: JSONObject.NULL while
            // open, or a timestamp once completed. A missing key is malformed data, not open work.
            if (!item.has("exit")) continue
            if (item.isNull("exit")) {
                // If corruption/manual data produces overlapping open sessions, keep the oldest
                // valid one stable until it closes. A future-dated tail becoming eligible must
                // not steal the widget display/action from the session that was already visible.
                if (entry < oldestValidOpenEntry) {
                    oldestValidOpen = item
                    oldestValidOpenEntry = entry
                }
                continue
            }

            val exit = item.optLong("exit", -1L)
            if (exit < entry || exit > now) continue
            if (entry >= latestCompletedEntry) {
                latestCompleted = item
                latestCompletedEntry = entry
            }
        }
        return oldestValidOpen ?: latestCompleted
    }

    /** Pause state belonging specifically to the selected, explicitly open session. */
    internal fun isPaused(session: JSONObject, now: Long = System.currentTimeMillis()): Boolean {
        if (!session.has("exit") || !session.isNull("exit")) return false
        val pauses = session.optJSONArray("pauses") ?: return false
        for (i in pauses.length() - 1 downTo 0) {
            val pause = pauses.optJSONObject(i) ?: continue
            val start = pause.optLong("start", -1L)
            if (start <= 0L || start > now) continue
            if (!pause.has("end")) continue
            if (pause.isNull("end")) return true
            val end = pause.optLong("end", -1L)
            if (end > start && now < end) return true
        }
        return false
    }
}
