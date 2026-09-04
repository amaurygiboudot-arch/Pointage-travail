package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
import org.json.JSONObject

/**
 * Valeurs structurées explicitement confirmées à partir d'un bulletin réel.
 *
 * Elles vivent dans le même SharedPreferences que V2PayslipStore afin d'être
 * incluses dans la sauvegarde V2 existante, sans modifier le format historique
 * des records brut/net.
 */
object PayslipObservedValuesStoreV2 {
    private const val PREFS = "horatrack_v2_payslips"
    private const val KEY = "observed_values_v2"

    private val allowedKeys = setOf(
        PayslipDocumentParserV2.KEY_GROSS,
        PayslipDocumentParserV2.KEY_NET_BEFORE_TAX,
        PayslipDocumentParserV2.KEY_NET_TAXABLE,
        PayslipDocumentParserV2.KEY_OVERTIME_GROSS,
        PayslipDocumentParserV2.KEY_PREMIUMS_GROSS,
        PayslipDocumentParserV2.KEY_MEAL_BASKETS,
        PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE,
        PayslipDocumentParserV2.KEY_PROVIDENT_EMPLOYEE
    )

    private val comparisonReadyKeys = allowedKeys - setOf(
        PayslipDocumentParserV2.KEY_PREMIUMS_GROSS,
        PayslipDocumentParserV2.KEY_MEAL_BASKETS
    )

    fun put(context: Context, recordId: String, values: Map<String, Double>) {
        require(recordId.isNotBlank()) { "Bulletin sans identifiant" }
        val safe = values.filter { (key, value) ->
            key in allowedKeys && value.isFinite() && value >= 0.0
        }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrElse { JSONObject() }
        if (safe.isEmpty()) {
            root.remove(recordId)
        } else {
            val item = JSONObject()
            safe.forEach { (key, value) -> item.put(key, value) }
            root.put(recordId, item)
        }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    /** Valeurs actuellement autorisées à entrer dans la comparaison automatique. */
    fun get(context: Context, recordId: String): Map<String, Double> =
        getAll(context, recordId).filterKeys { it in comparisonReadyKeys }

    /** Toutes les valeurs confirmées restent disponibles pour affichage/audit futur. */
    fun getAll(context: Context, recordId: String): Map<String, Double> {
        if (recordId.isBlank()) return emptyMap()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrElse { JSONObject() }
        val item = root.optJSONObject(recordId) ?: return emptyMap()
        return buildMap {
            allowedKeys.forEach { key ->
                if (item.has(key) && !item.isNull(key)) {
                    item.optDouble(key, Double.NaN).takeIf { it.isFinite() && it >= 0.0 }?.let { put(key, it) }
                }
            }
        }
    }

    fun remove(context: Context, recordId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY, "{}") ?: "{}") }.getOrElse { JSONObject() }
        if (root.remove(recordId) != null) prefs.edit().putString(KEY, root.toString()).apply()
    }
}
