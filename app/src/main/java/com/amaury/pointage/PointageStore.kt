package com.amaury.pointage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PointageStore {
    private const val PREFS = "pointage"
    private const val KEY = "data"

    fun load(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return JSONArray(prefs.getString(KEY, "[]") ?: "[]")
    }

    fun save(context: Context, data: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, data.toString())
            .apply()
    }

    fun hasOpen(context: Context): Boolean {
        val data = load(context)
        for (i in 0 until data.length()) {
            if (data.getJSONObject(i).isNull("exit")) return true
        }
        return false
    }

    fun entry(context: Context): Boolean {
        val data = load(context)
        if (hasOpen(context)) return false
        data.put(
            JSONObject()
                .put("entry", System.currentTimeMillis())
                .put("exit", JSONObject.NULL)
        )
        save(context, data)
        return true
    }

    fun exit(context: Context): Boolean {
        val data = load(context)
        for (i in data.length() - 1 downTo 0) {
            val item = data.getJSONObject(i)
            if (item.isNull("exit")) {
                item.put("exit", System.currentTimeMillis())
                save(context, data)
                return true
            }
        }
        return false
    }
}
