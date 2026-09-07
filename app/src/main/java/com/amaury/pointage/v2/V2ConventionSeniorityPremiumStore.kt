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

    fun rules(context: Context, idcc: String): List<ConventionSeniorityPremiumV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        return load(context).filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    fun saveConfirmed(context: Context, rule: ConventionSeniorityPremiumV2.Rule) {
        require(rule.structurallyValid()) { "Règle d'ancienneté conventionnelle invalide" }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val current = load(context).toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == rule.ruleId
        }
        current += rule.copy(idcc = normalized)
        persist(context, current)
    }

    private fun persist(context: Context, rules: List<ConventionSeniorityPremiumV2.Rule>) {
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionSeniorityPremiumV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, array.toString()).apply()
    }

    private fun load(context: Context): List<ConventionSeniorityPremiumV2.Rule> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RULES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decode(array.optJSONObject(index) ?: continue)?.takeIf { it.structurallyValid() }?.let(::add)
            }
        }
    }

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
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.valueOf(obj.getString("extensionStatus"))
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
