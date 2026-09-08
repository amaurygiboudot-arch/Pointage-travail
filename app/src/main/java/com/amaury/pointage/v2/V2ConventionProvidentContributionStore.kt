package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/** Cache LOCAL des cotisations conventionnelles de prévoyance vérifiées dans KALI. */
object V2ConventionProvidentContributionStore {
    private const val PREFS = "horatrack_v2_convention_provident_contribution_rules"
    private const val KEY_VERIFIED = "verified_rules"

    fun rules(context: Context): List<ConventionProvidentContributionV2.Rule> = load(context)

    fun rules(context: Context, idcc: String): List<ConventionProvidentContributionV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        return load(context).filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    /** Une preuve KALI persistable doit avoir un KALITEXT parent exact et une règle calculable. */
    internal fun acceptsVerifiedRule(rule: ConventionProvidentContributionV2.Rule): Boolean =
        rule.structurallyValid() &&
            rule.conventionScopeKey?.trim()?.uppercase(Locale.ROOT)?.matches(Regex("^KALITEXT\\d+$")) == true

    fun saveVerified(context: Context, rule: ConventionProvidentContributionV2.Rule) {
        require(acceptsVerifiedRule(rule)) {
            "Règle de cotisation prévoyance invalide ou sans périmètre KALI exact"
        }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val normalizedStatus = rule.professionalStatus?.trim()?.uppercase(Locale.ROOT)
        val normalizedScope = rule.conventionScopeKey!!.trim().uppercase(Locale.ROOT)
        val current = load(context).toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == rule.ruleId
        }
        current += rule.copy(
            idcc = normalized,
            professionalStatus = normalizedStatus,
            conventionScopeKey = normalizedScope
        )
        persist(context, current)
    }

    fun delete(context: Context, idcc: String, ruleId: String) {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        persist(context, load(context).filterNot {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == ruleId
        })
    }

    private fun persist(context: Context, rules: List<ConventionProvidentContributionV2.Rule>) {
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionProvidentContributionV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.classification.label() }
                .thenBy { it.professionalStatus.orEmpty() }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_VERIFIED, array.toString()).apply()
    }

    private fun load(context: Context): List<ConventionProvidentContributionV2.Rule> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VERIFIED, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decode(array.optJSONObject(index) ?: continue)
                    ?.takeIf(::acceptsVerifiedRule)
                    ?.let(::add)
            }
        }
    }

    private fun encode(rule: ConventionProvidentContributionV2.Rule): JSONObject {
        val tiers = JSONArray()
        rule.tiers.forEach { tier ->
            val bands = JSONArray()
            tier.bands.forEach { band ->
                bands.put(
                    JSONObject()
                        .put("label", band.label)
                        .put("lowerCeilingMultiple", band.lowerCeilingMultiple)
                        .put("upperCeilingMultiple", band.upperCeilingMultiple)
                        .put("employeeRate", band.employeeRate)
                        .put("employerRate", band.employerRate)
                )
            }
            tiers.put(
                JSONObject()
                    .put("minimumSeniorityMonths", tier.minimumSeniorityMonths)
                    .put("bands", bands)
            )
        }
        val ani = JSONArray()
        rule.aniCategories.sortedBy { it.name }.forEach { ani.put(it.name) }
        return JSONObject()
            .put("idcc", ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc))
            .put("ruleId", rule.ruleId)
            .put("effectiveFrom", rule.effectiveFrom.toString())
            .put("effectiveTo", rule.effectiveTo?.toString())
            .put("classification", encodeClassification(rule.classification))
            .put("professionalStatus", rule.professionalStatus)
            .put("aniCategories", ani)
            .put("tiers", tiers)
            .put("source", rule.source)
            .put("conventionScopeKey", rule.conventionScopeKey)
            .put("extensionStatus", rule.extensionStatus.name)
            .put("extensionEffectiveFrom", rule.extensionEffectiveFrom?.toString())
    }

    private fun decode(obj: JSONObject): ConventionProvidentContributionV2.Rule? = runCatching {
        val aniJson = obj.optJSONArray("aniCategories") ?: JSONArray()
        val ani = buildSet {
            for (index in 0 until aniJson.length()) {
                add(ProtectionCategoryV2.AniCategory.valueOf(aniJson.getString(index)))
            }
        }
        val tiersJson = obj.getJSONArray("tiers")
        val tiers = buildList {
            for (index in 0 until tiersJson.length()) {
                val tier = tiersJson.getJSONObject(index)
                val bandsJson = tier.getJSONArray("bands")
                val bands = buildList {
                    for (bandIndex in 0 until bandsJson.length()) {
                        val band = bandsJson.getJSONObject(bandIndex)
                        add(
                            ConventionProvidentContributionV2.Band(
                                label = band.getString("label"),
                                lowerCeilingMultiple = band.getDouble("lowerCeilingMultiple"),
                                upperCeilingMultiple = if (band.isNull("upperCeilingMultiple")) null else band.getDouble("upperCeilingMultiple"),
                                employeeRate = band.getDouble("employeeRate"),
                                employerRate = band.getDouble("employerRate")
                            )
                        )
                    }
                }
                add(
                    ConventionProvidentContributionV2.SeniorityTier(
                        minimumSeniorityMonths = tier.getInt("minimumSeniorityMonths"),
                        bands = bands
                    )
                )
            }
        }
        ConventionProvidentContributionV2.Rule(
            idcc = obj.getString("idcc"),
            ruleId = obj.getString("ruleId"),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = text(obj, "effectiveTo")?.let(LocalDate::parse),
            classification = decodeClassification(obj.optJSONObject("classification") ?: JSONObject()),
            professionalStatus = text(obj, "professionalStatus")?.uppercase(Locale.ROOT),
            aniCategories = ani,
            tiers = tiers,
            source = obj.getString("source"),
            conventionScopeKey = text(obj, "conventionScopeKey")?.uppercase(Locale.ROOT),
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.valueOf(obj.getString("extensionStatus")),
            extensionEffectiveFrom = text(obj, "extensionEffectiveFrom")?.let(LocalDate::parse)
        )
    }.getOrNull()

    private fun encodeClassification(value: ConventionClassificationV2): JSONObject = JSONObject()
        .put("coefficient", value.coefficient)
        .put("level", value.level)
        .put("echelon", value.echelon)
        .put("position", value.position)
        .put("group", value.group)
        .put("category", value.category)
        .put("employment", value.employment)

    private fun decodeClassification(obj: JSONObject): ConventionClassificationV2 = ConventionClassificationV2(
        coefficient = if (obj.isNull("coefficient")) null else obj.optInt("coefficient").takeIf { it > 0 },
        level = text(obj, "level"),
        echelon = text(obj, "echelon"),
        position = text(obj, "position"),
        group = text(obj, "group"),
        category = text(obj, "category"),
        employment = text(obj, "employment")
    )

    private fun text(obj: JSONObject, key: String): String? =
        obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
}
