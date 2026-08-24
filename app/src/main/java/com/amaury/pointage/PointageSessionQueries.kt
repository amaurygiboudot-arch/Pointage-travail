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
        for (i in data.length() - 1 downTo 0) {
            val item = data.optJSONObject(i) ?: continue
            val entry = item.optLong("entry", -1L)
            if (entry <= 0L || entry > now) continue

            if (item.isNull("exit")) return item

            val exit = item.optLong("exit", -1L)
            if (exit >= entry) return item
        }
        return null
    }
}
