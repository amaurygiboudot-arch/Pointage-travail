package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionSicknessMaintenanceV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Cache local des barèmes maladie conventionnels déjà vérifiés dans la source officielle. */
object V2ConventionSicknessMaintenanceStore {
    private const val PREFS = "horatrack_v2_convention_sickness_maintenance"
    private const val KEY_RULES = "confirmed_rules"

    fun rules(context: Context, idcc: String): List<ConventionSicknessMaintenanceV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        return load(context).filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    fun saveConfirmed(context: Context, rule: ConventionSicknessMaintenanceV2.Rule) {
        require(rule.structurallyValid()) { "Règle de maintien maladie invalide" }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val current = load(context).toMutableList()
        current.removeAll { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == rule.ruleId }
        current += rule.copy(idcc = normalized)
        persist(context, current)
    }

    private fun persist(context: Context, rules: List<ConventionSicknessMaintenanceV2.Rule>) {
        val array = JSONArray()
        rules.sortedWith(compareBy<ConventionSicknessMaintenanceV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
            .thenBy { it.effectiveFrom }.thenBy { it.ruleId }).forEach { array.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RULES, array.toString()).apply()
    }

    private fun load(context: Context): List<ConventionSicknessMaintenanceV2.Rule> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RULES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decode(array.optJSONObject(index) ?: continue)?.takeIf { it.structurallyValid() }?.let(::add)
            }
        }
    }

    private fun encode(rule: ConventionSicknessMaintenanceV2.Rule): JSONObject {
        val tiers = JSONArray()
        rule.tiers.forEach { tier ->
            val bands = JSONArray()
            tier.bands.forEach { band ->
                bands.put(JSONObject().put("days", band.calendarDays).put("rate", band.targetRate).put("label", band.label))
            }
            tiers.put(JSONObject()
                .put("minimumSeniorityMonths", tier.minimumSeniorityMonths)
                .put("annualLimitDays", tier.annualLimitDays)
                .put("perStopLimitDays", tier.perStopLimitDays)
                .put("bands", bands))
        }
        return JSONObject()
            .put("idcc", ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc))
            .put("ruleId", rule.ruleId)
            .put("effectiveFrom", rule.effectiveFrom.toString())
            .put("effectiveTo", rule.effectiveTo?.toString())
            .put("classification", encodeClassification(rule.classification))
            .put("professionalStatus", rule.professionalStatus)
            .put("minimumSeniorityMonths", rule.minimumSeniorityMonths)
            .put("tiers", tiers)
            .put("referenceBasis", rule.referenceBasis.name)
            .put("waitingPolicy", rule.waitingPolicy.name)
            .put("waitingDays", rule.waitingDays)
            .put("ssCoverageAfterDays", rule.socialSecurityCoverageRequiredAfterDays)
            .put("source", rule.source)
            .put("extensionStatus", rule.extensionStatus.name)
            .put("extensionEffectiveFrom", rule.extensionEffectiveFrom?.toString())
    }

    private fun decode(obj: JSONObject): ConventionSicknessMaintenanceV2.Rule? = runCatching {
        val tiersJson = obj.getJSONArray("tiers")
        val tiers = buildList {
            for (index in 0 until tiersJson.length()) {
                val tier = tiersJson.getJSONObject(index)
                val bandsJson = tier.getJSONArray("bands")
                val bands = buildList {
                    for (bandIndex in 0 until bandsJson.length()) {
                        val band = bandsJson.getJSONObject(bandIndex)
                        add(ConventionSicknessMaintenanceV2.Band(band.getInt("days"), band.getDouble("rate"), band.getString("label")))
                    }
                }
                add(ConventionSicknessMaintenanceV2.SeniorityTier(
                    minimumSeniorityMonths = tier.getInt("minimumSeniorityMonths"),
                    bands = bands,
                    annualLimitDays = if (tier.isNull("annualLimitDays")) null else tier.getInt("annualLimitDays"),
                    perStopLimitDays = if (tier.isNull("perStopLimitDays")) null else tier.getInt("perStopLimitDays")
                ))
            }
        }
        ConventionSicknessMaintenanceV2.Rule(
            idcc = obj.getString("idcc"),
            ruleId = obj.getString("ruleId"),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = obj.optString("effectiveTo").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse),
            classification = decodeClassification(obj.optJSONObject("classification") ?: JSONObject()),
            professionalStatus = obj.optString("professionalStatus").takeIf { it.isNotBlank() && it != "null" },
            minimumSeniorityMonths = obj.getInt("minimumSeniorityMonths"),
            tiers = tiers,
            referenceBasis = ConventionSicknessMaintenanceV2.ReferenceBasis.valueOf(obj.getString("referenceBasis")),
            waitingPolicy = ConventionSicknessMaintenanceV2.WaitingPolicy.valueOf(obj.getString("waitingPolicy")),
            waitingDays = obj.optInt("waitingDays", 0),
            socialSecurityCoverageRequiredAfterDays = if (obj.isNull("ssCoverageAfterDays")) null else obj.getInt("ssCoverageAfterDays"),
            source = obj.getString("source"),
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.valueOf(obj.getString("extensionStatus")),
            extensionEffectiveFrom = obj.optString("extensionEffectiveFrom").takeIf { it.isNotBlank() && it != "null" }?.let(LocalDate::parse)
        )
    }.getOrNull()

    private fun encodeClassification(value: ConventionClassificationV2): JSONObject = JSONObject()
        .put("coefficient", value.coefficient).put("level", value.level).put("echelon", value.echelon)
        .put("position", value.position).put("group", value.group).put("category", value.category).put("employment", value.employment)

    private fun decodeClassification(obj: JSONObject): ConventionClassificationV2 = ConventionClassificationV2(
        coefficient = if (obj.isNull("coefficient")) null else obj.optInt("coefficient").takeIf { it > 0 },
        level = text(obj, "level"), echelon = text(obj, "echelon"), position = text(obj, "position"),
        group = text(obj, "group"), category = text(obj, "category"), employment = text(obj, "employment")
    )

    private fun text(obj: JSONObject, key: String) = obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
}
