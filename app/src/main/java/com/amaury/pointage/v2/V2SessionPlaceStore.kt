package com.amaury.pointage.v2

import android.content.Context
import org.json.JSONArray

/**
 * Conserve uniquement l'identifiant de zone et son libellé/adresse déjà configuré.
 * Aucun historique de coordonnées GPS brutes n'est créé ici.
 */
object V2SessionPlaceStore {
    private const val PREFS = "horatrack_v2_test_runtime"
    private const val KEY_PLACE_ID = "place_id"
    private const val KEY_PLACE_LABEL = "place_label"

    fun setCurrent(context: Context, placeId: String?, placeLabel: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        placeId?.trim()?.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_PLACE_ID, it) } ?: editor.remove(KEY_PLACE_ID)
        placeLabel?.trim()?.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_PLACE_LABEL, it) } ?: editor.remove(KEY_PLACE_LABEL)
        editor.apply()
    }

    fun clearCurrent(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PLACE_ID)
            .remove(KEY_PLACE_LABEL)
            .apply()
    }

    fun currentId(context: Context): String? = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PLACE_ID, null)?.trim()?.takeIf { it.isNotBlank() }

    fun currentLabel(context: Context): String? = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PLACE_LABEL, null)?.trim()?.takeIf { it.isNotBlank() }

    fun enrichLatestHistory(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val placeId = currentId(context)
        val placeLabel = currentLabel(context)
        if (placeId == null && placeLabel == null) return
        val history = runCatching { JSONArray(prefs.getString("history", "[]") ?: "[]") }.getOrElse { JSONArray() }
        if (history.length() == 0) return
        val item = history.optJSONObject(history.length() - 1) ?: return
        placeId?.let { item.put("placeId", it) }
        placeLabel?.let { item.put("placeLabel", it) }
        prefs.edit().putString("history", history.toString()).apply()
    }
}
