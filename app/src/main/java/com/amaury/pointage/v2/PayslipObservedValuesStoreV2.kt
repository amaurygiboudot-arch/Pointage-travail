package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.PayslipEngineV2
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
    internal const val KEY = "observed_values_v2"
    private const val STORAGE_WARNING =
        "Valeurs observées des bulletins : stockage local illisible ou incohérent ; la comparaison automatique est bloquée."

    internal val allowedKeys = setOf(
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
        val context = SalaryAccountScope.bindContext(context)
        fun unavailable(): ReadResult = ReadResult(valuesByRecord = emptyMap(), reliable = false, warnings = listOf(SalaryAccountScope.AUTH_WARNING))
        if (!SalaryAccountScope.available(context)) return unavailable()
        fun readInScope(): ReadResult {
            val prefs = SalaryAccountScope.preferences(context, PREFS)
            if (!prefs.contains(KEY)) return ReadResult(emptyMap(), true, emptyList())
            val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return corruptResult()
            return decodeValues(raw)
        }
        val result = readInScope()
        return if (SalaryAccountScope.available(context)) result else unavailable()
    }

    internal fun decodeValues(raw: String): ReadResult {
        if (raw.isBlank() || !PayslipMonetaryJsonValidationV2.observed(raw)) return corruptResult()
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
                    val value = (item.opt(key) as? Number)?.let { PayslipEngineV2.parseAmount(it.toString()) }
                    if (value == null || !PayslipEngineV2.isValidAmount(value)) {
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

    fun put(context: Context, recordId: String, values: Map<String, Double>, evidence: Map<String, String> = emptyMap()): Boolean {
        val context = SalaryAccountScope.bindContext(context)
        return PayslipImportStoreV2.withStorageAccess(context, { false }) {
            if (recordId.isBlank()) return@withStorageAccess false
            if (values.any { (key, value) -> key !in allowedKeys || !PayslipEngineV2.isValidAmount(value) }) return@withStorageAccess false
            val stored = readResult(context)
            if (!stored.reliable) return@withStorageAccess false
            val updated = stored.valuesByRecord.toMutableMap()
            if (values.isEmpty()) updated.remove(recordId) else updated[recordId] = values.toMap()
            saveWithEvidence(context, updated, recordId, evidence.filterKeys { it in values })
        }
    }

    /** Valeurs actuellement autorisées à entrer dans la comparaison automatique. */
    fun get(context: Context, recordId: String): Map<String, Double> {
        val context = SalaryAccountScope.bindContext(context)
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
        val context = SalaryAccountScope.bindContext(context)
        val stored = readResult(context)
        if (!stored.reliable || recordId.isBlank()) return emptyMap()
        return stored.valuesByRecord[recordId].orEmpty()
    }

    fun remove(context: Context, recordId: String): Boolean {
        val context = SalaryAccountScope.bindContext(context)
        return PayslipImportStoreV2.withStorageAccess(context, { false }) {
            if (recordId.isBlank()) return@withStorageAccess false
            val stored = readResult(context)
            if (!stored.reliable) return@withStorageAccess false
            if (recordId !in stored.valuesByRecord) return@withStorageAccess true
            val updated = stored.valuesByRecord.toMutableMap().apply { remove(recordId) }
            saveWithEvidence(context, updated, recordId, emptyMap())
        }
    }

    private fun saveWithEvidence(context: Context, values: Map<String, Map<String, Double>>, recordId: String, evidence: Map<String, String>): Boolean {
        val context = SalaryAccountScope.bindContext(context)
        val prefs = SalaryAccountScope.preferences(context, PREFS)
        val root = runCatching { JSONObject(prefs.getString("observed_evidence_v2", "{}") ?: "{}") }.getOrNull() ?: return false
        if (evidence.isEmpty()) root.remove(recordId) else {
            val item = JSONObject()
            evidence.forEach { (key, excerpt) -> if (key in allowedKeys) item.put(key, excerpt.take(300)) }
            root.put(recordId, item)
        }
        return save(context, values, root.toString())
    }

    /** Bounded per-field excerpts only; the full OCR text is never retained. */
    fun evidence(context: Context, recordId: String): Map<String, String> = runCatching {
        val prefs = SalaryAccountScope.preferences(context, PREFS)
        val item = JSONObject(prefs.getString("observed_evidence_v2", "{}") ?: "{}").optJSONObject(recordId) ?: return@runCatching emptyMap()
        allowedKeys.mapNotNull { key -> (item.opt(key) as? String)?.takeIf { it.length <= 300 }?.let { key to it } }.toMap()
    }.getOrDefault(emptyMap())

    private fun save(context: Context, valuesByRecord: Map<String, Map<String, Double>>, evidenceJson: String): Boolean {
        val context = SalaryAccountScope.bindContext(context)
        val encoded = encodeValues(valuesByRecord) ?: return false
        return runCatching {
            SalaryAccountScope.preferences(context, PREFS)
                .edit().putString(KEY, encoded).putString("observed_evidence_v2", evidenceJson).commit()
        }.getOrDefault(false)
    }

    internal fun encodeValues(valuesByRecord: Map<String, Map<String, Double>>): String? {
        if (valuesByRecord.any { (recordId, values) ->
                recordId.isBlank() || values.isEmpty() ||
                    values.any { (key, value) -> key !in allowedKeys || !PayslipEngineV2.isValidAmount(value) }
            }) return null
        val root = JSONObject()
        valuesByRecord.toSortedMap().forEach { (recordId, values) ->
            val item = JSONObject()
            values.toSortedMap().forEach { (key, value) -> item.put(key, value) }
            root.put(recordId, item)
        }
        return root.toString()
    }

    internal fun decodeEvidence(raw: String): Map<String, Map<String, String>>? = runCatching {
        val root = JSONObject(raw)
        buildMap {
            for (recordId in root.keys()) {
                require(recordId.isNotBlank() && recordId == recordId.trim())
                val item = root.getJSONObject(recordId)
                put(recordId, buildMap {
                    for (key in item.keys()) {
                        require(key in allowedKeys)
                        val excerpt = item.get(key) as? String ?: error("Invalid excerpt")
                        require(excerpt.length <= 300)
                        put(key, excerpt)
                    }
                })
            }
        }
    }.getOrNull()

    internal fun encodeEvidence(evidence: Map<String, Map<String, String>>): String = JSONObject().apply {
        evidence.toSortedMap().forEach { (id, values) ->
            put(id, JSONObject().apply { values.toSortedMap().forEach { (key, value) -> put(key, value) } })
        }
    }.toString()

    private fun corruptResult() = ReadResult(emptyMap(), false, listOf(STORAGE_WARNING))
}
