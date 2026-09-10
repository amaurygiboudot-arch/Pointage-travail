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
    private const val STORAGE_WARNING =
        "Valeurs observées des bulletins : stockage local illisible ou incohérent ; la comparaison automatique est bloquée."

    private val allowedKeys = setOf(
        PayslipDocumentParserV2.KEY_GROSS,
        PayslipDocumentParserV2.KEY_NET_BEFORE_TAX,
        PayslipDocumentParserV2.KEY_NET_TAXABLE,
        PayslipDocumentParserV2.KEY_OVERTIME_GROSS,
        PayslipDocumentParserV2.KEY_PREMIUMS_GROSS,
        PayslipDocumentParserV2.KEY_MEAL_BASKETS,
        PayslipDocumentParserV2.KEY_MUTUAL_EMPLOYEE,
        PayslipDocumentParserV2.KEY_PROVIDENT_EMPLOYEE,
        PayslipDocumentParserV2.KEY_COMPLEMENTARY_RETIREMENT_EMPLOYEE
    )

    // Les paniers disposent désormais d'une valeur attendue V2 sûre. Les primes et la retraite
    // complémentaire observée restent hors comparaison tant que toutes leurs sources ou la
    // répartition salariale propre à l'entreprise ne sont pas intégrées de façon prouvée.
    private val comparisonReadyKeys = allowedKeys - setOf(
        PayslipDocumentParserV2.KEY_PREMIUMS_GROSS,
        PayslipDocumentParserV2.KEY_COMPLEMENTARY_RETIREMENT_EMPLOYEE
    )

    data class ReadResult(
        val valuesByRecord: Map<String, Map<String, Double>>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    internal fun readResult(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return ReadResult(emptyMap(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return corruptResult()
        return decodeValues(raw)
    }

    internal fun decodeValues(raw: String): ReadResult {
        if (raw.isBlank()) return corruptResult()
        return runCatching {
            val root = JSONObject(raw)
            var malformed = false
            val values = linkedMapOf<String, Map<String, Double>>()
            val recordIds = root.keys()
            while (recordIds.hasNext()) {
                val recordId = recordIds.next().trim()
                if (recordId.isBlank()) {
                    malformed = true
                    continue
                }
                val item = root.optJSONObject(recordId)
                if (item == null) {
                    malformed = true
                    continue
                }
                val parsed = linkedMapOf<String, Double>()
                val keys = item.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key !in allowedKeys) {
                        malformed = true
                        continue
                    }
                    val value = (item.opt(key) as? Number)?.toDouble()
                    if (value == null || !value.isFinite() || value < 0.0) {
                        malformed = true
                        continue
                    }
                    parsed[key] = value
                }
                if (parsed.isEmpty()) {
                    malformed = true
                    continue
                }
                values[recordId] = parsed
            }
            ReadResult(
                valuesByRecord = values,
                reliable = !malformed,
                warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
            )
        }.getOrElse { corruptResult() }
    }

    fun put(context: Context, recordId: String, values: Map<String, Double>): Boolean {
        if (recordId.isBlank()) return false
        if (values.any { (key, value) -> key !in allowedKeys || !value.isFinite() || value < 0.0 }) return false

        val stored = readResult(context)
        if (!stored.reliable) return false
        val updated = stored.valuesByRecord.toMutableMap()
        if (values.isEmpty()) updated.remove(recordId) else updated[recordId] = values.toMap()
        return save(context, updated)
    }

    /** Valeurs actuellement autorisées à entrer dans la comparaison automatique. */
    fun get(context: Context, recordId: String): Map<String, Double> {
        val stored = readResult(context)
        if (!stored.reliable) return emptyMap()
        return comparisonValues(stored, recordId)
    }

    internal fun comparisonValues(stored: ReadResult, recordId: String): Map<String, Double> {
        if (!stored.reliable || recordId.isBlank()) return emptyMap()
        return stored.valuesByRecord[recordId].orEmpty().filterKeys { it in comparisonReadyKeys }
    }

    /** Toutes les valeurs confirmées restent disponibles pour affichage/audit futur. */
    fun getAll(context: Context, recordId: String): Map<String, Double> {
        val stored = readResult(context)
        if (!stored.reliable || recordId.isBlank()) return emptyMap()
        return stored.valuesByRecord[recordId].orEmpty()
    }

    fun remove(context: Context, recordId: String): Boolean {
        if (recordId.isBlank()) return false
        val stored = readResult(context)
        if (!stored.reliable) return false
        if (recordId !in stored.valuesByRecord) return true
        val updated = stored.valuesByRecord.toMutableMap().apply { remove(recordId) }
        return save(context, updated)
    }

    private fun save(context: Context, valuesByRecord: Map<String, Map<String, Double>>): Boolean {
        if (valuesByRecord.any { (recordId, values) ->
                recordId.isBlank() || values.isEmpty() ||
                    values.any { (key, value) -> key !in allowedKeys || !value.isFinite() || value < 0.0 }
            }) return false

        val root = JSONObject()
        valuesByRecord.toSortedMap().forEach { (recordId, values) ->
            val item = JSONObject()
            values.toSortedMap().forEach { (key, value) -> item.put(key, value) }
            root.put(recordId, item)
        }
        return runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, root.toString()).commit()
        }.getOrDefault(false)
    }

    private fun corruptResult() = ReadResult(emptyMap(), false, listOf(STORAGE_WARNING))
}
