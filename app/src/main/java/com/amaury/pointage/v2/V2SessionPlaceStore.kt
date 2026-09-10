package com.amaury.pointage.v2

import android.content.Context

/**
 * Conserve uniquement l'identifiant de zone et son libellé/adresse déjà configuré.
 * Aucun historique de coordonnées GPS brutes n'est créé ici.
 */
object V2SessionPlaceStore {
    private const val PREFS = "horatrack_v2_test_runtime"
    private const val KEY_PLACE_ID = "place_id"
    private const val KEY_PLACE_LABEL = "place_label"

    fun setCurrent(context: Context, placeId: String?, placeLabel: String?): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        placeId?.trim()?.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_PLACE_ID, it) } ?: editor.remove(KEY_PLACE_ID)
        placeLabel?.trim()?.takeIf { it.isNotBlank() }?.let { editor.putString(KEY_PLACE_LABEL, it) } ?: editor.remove(KEY_PLACE_LABEL)
        return editor.commit()
    }

    fun clearCurrent(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PLACE_ID)
            .remove(KEY_PLACE_LABEL)
            .commit()

    fun currentId(context: Context): String? = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PLACE_ID, null)?.trim()?.takeIf { it.isNotBlank() }

    fun currentLabel(context: Context): String? = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(KEY_PLACE_LABEL, null)?.trim()?.takeIf { it.isNotBlank() }

    fun enrichLatestHistory(context: Context): Boolean {
        val placeId = currentId(context)
        val placeLabel = currentLabel(context)
        if (placeId == null && placeLabel == null) return true

        val stored = V2RuntimeHistoryGuardV2.read(context)
        if (!stored.reliable) return false
        val history = stored.history
        if (history.length() == 0) return true
        val item = history.optJSONObject(history.length() - 1) ?: return false
        placeId?.let { item.put("placeId", it) }
        placeLabel?.let { item.put("placeLabel", it) }
        return V2RuntimeHistoryGuardV2.save(context, history)
    }
}
