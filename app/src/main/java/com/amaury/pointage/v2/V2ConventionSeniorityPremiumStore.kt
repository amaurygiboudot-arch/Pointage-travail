package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSeniorityPremiumV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Règles d'ancienneté conventionnelles confirmées, historisées par IDCC. */
object V2ConventionSeniorityPremiumStore {
    private const val PREFS = "horatrack_v2_convention_seniority_rules"
    private const val KEY_RULES = "confirmed_rules"
    private const val STORAGE_WARNING =
        "KALI ancienneté : historique local des règles conventionnelles incohérent ; aucune prime d'ancienneté ne peut être déduite de ce stockage."

    data class ReadResult(
        val rules: List<ConventionSeniorityPremiumV2.Rule>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun readConfirmed(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_RULES)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_RULES, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeConfirmed(raw)
    }

    fun rules(context: Context, idcc: String): List<ConventionSeniorityPremiumV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.rules.filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    fun saveConfirmed(context: Context, rule: ConventionSeniorityPremiumV2.Rule) {
        require(rule.structurallyValid()) { "Règle d'ancienneté conventionnelle invalide" }
        val stored = readConfirmed(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val current = stored.rules.toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == rule.ruleId
        }
        current += rule.copy(idcc = normalized)
        check(current.all { it.structurallyValid() }) { "KALI ancienneté : historique conventionnel invalide." }
        check(!hasDuplicateRuleIds(current)) { "KALI ancienneté : identifiant de règle dupliqué." }
        persist(context, current)
    }

    private fun persist(context: Context, rules: List<ConventionSeniorityPremiumV2.Rule>) {
        check(rules.all { it.structurallyValid() }) { "KALI ancienneté : historique conventionnel invalide." }
        check(!hasDuplicateRuleIds(rules)) { "KALI ancienneté : identifiant de règle dupliqué." }
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionSeniorityPremiumV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RULES, array.toString())
                .commit()
        }.getOrDefault(false)
        check(saved) { "KALI ancienneté : stockage local des règles impossible." }
    }

    internal fun decodeConfirmed(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val rules = mutableListOf<ConventionSeniorityPremiumV2.Rule>()
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

    private fun hasDuplicateRuleIds(rules: List<ConventionSeniorityPremiumV2.Rule>): Boolean =
        rules.groupBy {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) to it.ruleId
        }.values.any { it.size > 1 }

    private fun encode(rule: ConventionSeniorityPremiumV2.Rule): JSONObject {
        val steps = JSONArray()
        rule.steps.forEach { step ->
            steps.put(JSONObject()
                .put("years", step.years)
                .put("rate", step.rate)
                .put("fixedMonthlyAmount", step.fixedMonthlyAmount))
        }
        return JSONObject()
            .put("idcc", ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc))
            .put("ruleId", rule.ruleId)
            .put("effectiveFrom", rule.effectiveFrom.toString())
            .put("effectiveTo", rule.effectiveTo?.toString())
            .put("classification", encodeClassification(rule.classification))
            .put("basis", rule.basis.name)
            .put("steps", steps)
            .put("includeConfirmedMonthlySupplement", rule.includeConfirmedMonthlySupplement)
            .put("source", rule.source)
            .put("extensionStatus", rule.extensionStatus.name)
            .put("extensionEffectiveFrom", rule.extensionEffectiveFrom?.toString())
    }

    private fun encodeClassification(value: ConventionClassificationV2): JSONObject = JSONObject()
        .put("coefficient", value.coefficient)
        .put("level", value.level)
        .put("echelon", value.echelon)
        .put("position", value.position)
        .put("group", value.group)
        .put("category", value.category)
        .put("employment", value.employment)

    private fun decode(obj: JSONObject): ConventionSeniorityPremiumV2.Rule? = runCatching {
        val stepsJson = obj.getJSONArray("steps")
        val steps = buildList {
            for (index in 0 until stepsJson.length()) {
                val step = stepsJson.getJSONObject(index)
                add(ConventionSeniorityPremiumV2.Step(
                    years = step.getInt("years"),
                    rate = if (step.isNull("rate")) null else step.getDouble("rate"),
                    fixedMonthlyAmount = if (step.isNull("fixedMonthlyAmount")) null else step.getDouble("fixedMonthlyAmount")
                ))
            }
        }
        ConventionSeniorityPremiumV2.Rule(
            idcc = obj.getString("idcc"),
            ruleId = obj.getString("ruleId"),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = obj.optString("effectiveTo").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse),
            classification = decodeClassification(obj.optJSONObject("classification") ?: JSONObject()),
            basis = ConventionSeniorityPremiumV2.Basis.valueOf(obj.getString("basis")),
            steps = steps,
            includeConfirmedMonthlySupplement = obj.optBoolean("includeConfirmedMonthlySupplement", false),
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
