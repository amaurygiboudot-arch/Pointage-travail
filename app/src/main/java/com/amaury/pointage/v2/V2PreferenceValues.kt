package com.amaury.pointage.v2

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Lossless codec for durable local restore images, including values excluded from cloud exports. */
internal object V2PreferenceValues {
    fun encode(values: Map<String, *>): JSONObject = JSONObject().apply {
        values.forEach { (key, value) ->
            val item = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> {
                    require(value.all { it is String })
                    JSONObject().put("t", "set").put("v", JSONArray(value.toList().sortedBy { it as String }))
                }
                else -> error("Type de préférence non pris en charge : $key")
            }
            put(key, item)
        }
    }

    fun decode(value: JSONObject): Map<String, Any?> {
        require(V2BackupManager.isValidTypedPreferencePayload(value)) { "Image de restauration invalide" }
        return value.keys().asSequence().associateWith { key ->
            val item = value.getJSONObject(key)
            when (item.getString("t")) {
                "s" -> item.getString("v")
                "b" -> item.getBoolean("v")
                "i" -> ((item.get("v") as? Number)?.toLong() ?: item.getString("v").trim().toLong()).toInt()
                "l" -> (item.get("v") as? Number)?.toLong() ?: item.getString("v").trim().toLong()
                "f" -> (item.get("v") as Number).toFloat()
                "set" -> item.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> error("Type de préférence invalide")
            }
        }
    }

    fun replace(preferences: SharedPreferences, values: Map<String, *>): Boolean {
        val editor = preferences.edit()
        editor.clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> {
                    require(value.all { it is String })
                    editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
                else -> error("Type de préférence invalide : $key")
            }
        }
        return editor.commit()
    }
}
