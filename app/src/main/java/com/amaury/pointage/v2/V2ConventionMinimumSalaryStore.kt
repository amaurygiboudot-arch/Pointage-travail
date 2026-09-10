package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Cache local des minima conventionnels officiellement confirmés.
 *
 * Il ne remplace pas Légifrance : seules des règles déjà vérifiées et datées
 * doivent être enregistrées ici. Les règles sont indexées par IDCC + ruleId et
 * conservent leur historique d'effet.
 */
object V2ConventionMinimumSalaryStore {
    private const val PREFS = "horatrack_v2_convention_minimum_salary_rules"
    private const val KEY_CONFIRMED = "confirmed_rules"
    private const val STORAGE_WARNING =
        "KALI minimum salarial : historique local des barèmes conventionnels incohérent ; aucun minimum ne peut être déduit de ce stockage."

    data class ReadResult(
        val rules: List<ConventionMinimumSalaryV2.Rule>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun readConfirmed(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_CONFIRMED)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_CONFIRMED, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeConfirmed(raw)
    }

    fun rules(context: Context): List<ConventionMinimumSalaryV2.Rule> {
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.rules
    }

    fun rules(context: Context, idcc: String): List<ConventionMinimumSalaryV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.rules.filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    fun saveConfirmed(context: Context, rule: ConventionMinimumSalaryV2.Rule) {
        require(rule.structurallyValid()) { "Règle de minimum conventionnel invalide" }
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val current = stored.rules.toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == rule.ruleId
        }
        current += rule.copy(idcc = normalized)
        check(current.all { it.structurallyValid() }) { "KALI minimum salarial : historique conventionnel invalide." }
        check(!hasDuplicateRuleIds(current)) { "KALI minimum salarial : identifiant de barème dupliqué." }
        persist(context, current)
    }

    fun delete(context: Context, idcc: String, ruleId: String) {
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val current = stored.rules.filterNot {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == ruleId
        }
        persist(context, current)
    }

    private fun persist(context: Context, rules: List<ConventionMinimumSalaryV2.Rule>) {
        check(rules.all { it.structurallyValid() }) { "KALI minimum salarial : historique conventionnel invalide." }
        check(!hasDuplicateRuleIds(rules)) { "KALI minimum salarial : identifiant de barème dupliqué." }
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionMinimumSalaryV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CONFIRMED, array.toString())
                .commit()
        }.getOrDefault(false)
        check(saved) { "KALI minimum salarial : stockage local des barèmes impossible." }
    }

    internal fun decodeConfirmed(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val rules = mutableListOf<ConventionMinimumSalaryV2.Rule>()
        var malformed = false
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val rule = obj?.let(::decode)
            if (rule == null || !rule.structurallyValid()) {
                malformed = true
            } else {
                rules += rule
            }
        }
        if (!malformed && hasDuplicateRuleIds(rules)) malformed = true
        return ReadResult(
            rules = rules,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun hasDuplicateRuleIds(rules: List<ConventionMinimumSalaryV2.Rule>): Boolean =
        rules.groupBy {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) to it.ruleId
        }.values.any { it.size > 1 }

    private fun encode(rule: ConventionMinimumSalaryV2.Rule): JSONObject = JSONObject()
        .put("idcc", ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc))
        .put("ruleId", rule.ruleId)
        .put("effectiveFrom", rule.effectiveFrom.toString())
        .put("effectiveTo", rule.effectiveTo?.toString())
        .put("amount", rule.amount)
        .put("periodicity", rule.periodicity.name)
        .put("source", rule.source)
        .put("extensionStatus", rule.extensionStatus.name)
        .put("extensionEffectiveFrom", rule.extensionEffectiveFrom?.toString())
        .put("classification", encodeClassification(rule.classification))

    private fun encodeClassification(value: ConventionClassificationV2): JSONObject = JSONObject()
        .put("coefficient", value.coefficient)
        .put("level", value.level)
        .put("echelon", value.echelon)
        .put("position", value.position)
        .put("group", value.group)
        .put("category", value.category)
        .put("employment", value.employment)

    private fun decode(obj: JSONObject): ConventionMinimumSalaryV2.Rule? = runCatching {
        ConventionMinimumSalaryV2.Rule(
            idcc = obj.getString("idcc"),
            ruleId = obj.getString("ruleId"),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = obj.optString("effectiveTo").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse),
            classification = decodeClassification(obj.optJSONObject("classification") ?: JSONObject()),
            amount = obj.getDouble("amount"),
            periodicity = ConventionMinimumSalaryV2.Periodicity.valueOf(obj.getString("periodicity")),
            source = obj.getString("source"),
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.valueOf(obj.getString("extensionStatus")),
            extensionEffectiveFrom = obj.optString("extensionEffectiveFrom").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse)
        )
    }.getOrNull()

    private fun decodeClassification(obj: JSONObject): ConventionClassificationV2 = ConventionClassificationV2(
        coefficient = if (obj.isNull("coefficient")) null else obj.optInt("coefficient").takeIf { it > 0 },
        level = obj.optString("level").takeIf { it.isNotBlank() && it != "null" },
        echelon = obj.optString("echelon").takeIf { it.isNotBlank() && it != "null" },
        position = obj.optString("position").takeIf { it.isNotBlank() && it != "null" },
        group = obj.optString("group").takeIf { it.isNotBlank() && it != "null" },
        category = obj.optString("category").takeIf { it.isNotBlank() && it != "null" },
        employment = obj.optString("employment").takeIf { it.isNotBlank() && it != "null" }
    )
}
