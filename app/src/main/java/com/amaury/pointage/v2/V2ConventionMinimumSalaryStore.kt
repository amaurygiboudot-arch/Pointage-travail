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

    fun rules(context: Context): List<ConventionMinimumSalaryV2.Rule> = load(context)

    fun rules(context: Context, idcc: String): List<ConventionMinimumSalaryV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        return load(context).filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    fun saveConfirmed(context: Context, rule: ConventionMinimumSalaryV2.Rule) {
        require(rule.structurallyValid()) { "Règle de minimum conventionnel invalide" }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val current = load(context).toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == rule.ruleId
        }
        current += rule.copy(idcc = normalized)
        persist(context, current)
    }

    fun delete(context: Context, idcc: String, ruleId: String) {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val current = load(context).filterNot {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == ruleId
        }
        persist(context, current)
    }

    private fun persist(context: Context, rules: List<ConventionMinimumSalaryV2.Rule>) {
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionMinimumSalaryV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIRMED, array.toString())
            .apply()
    }

    private fun load(context: Context): List<ConventionMinimumSalaryV2.Rule> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONFIRMED, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decode(array.optJSONObject(index) ?: continue)?.takeIf { it.structurallyValid() }?.let(::add)
            }
        }
    }

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
