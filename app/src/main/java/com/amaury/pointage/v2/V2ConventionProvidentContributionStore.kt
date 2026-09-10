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
    private const val STORAGE_WARNING =
        "KALI cotisations prévoyance : stockage local des règles vérifiées incohérent ; aucun barème ni aucune absence de cotisation ne peut être déduit de ce stockage."

    data class ReadResult(
        val rules: List<ConventionProvidentContributionV2.Rule>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun readVerified(context: Context): ReadResult {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_VERIFIED)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY_VERIFIED, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeVerified(raw)
    }

    fun rules(context: Context): List<ConventionProvidentContributionV2.Rule> {
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.rules
    }

    fun rules(context: Context, idcc: String): List<ConventionProvidentContributionV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.rules.filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    /** Une preuve KALI persistable doit avoir un KALITEXT parent exact et une règle calculable. */
    internal fun acceptsVerifiedRule(rule: ConventionProvidentContributionV2.Rule): Boolean =
        rule.structurallyValid() &&
            rule.conventionScopeKey?.trim()?.uppercase(Locale.ROOT)?.matches(Regex("^KALITEXT\\d+$")) == true

    internal fun acceptsVerifiedPackage(rules: List<ConventionProvidentContributionV2.Rule>): Boolean =
        rules.all(::acceptsVerifiedRule) && !hasDuplicateRuleId(rules)

    fun saveVerified(context: Context, rule: ConventionProvidentContributionV2.Rule) {
        require(acceptsVerifiedRule(rule)) {
            "Règle de cotisation prévoyance invalide ou sans périmètre KALI exact"
        }
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val normalizedStatus = rule.professionalStatus?.trim()?.uppercase(Locale.ROOT)
        val normalizedScope = rule.conventionScopeKey!!.trim().uppercase(Locale.ROOT)
        val normalizedRuleId = rule.ruleId.trim().uppercase(Locale.ROOT)
        val current = stored.rules.toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized &&
                it.ruleId.trim().uppercase(Locale.ROOT) == normalizedRuleId
        }
        current += rule.copy(
            idcc = normalized,
            ruleId = rule.ruleId.trim(),
            professionalStatus = normalizedStatus,
            conventionScopeKey = normalizedScope
        )
        persist(context, current)
    }

    fun delete(context: Context, idcc: String, ruleId: String) {
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val normalizedRuleId = ruleId.trim().uppercase(Locale.ROOT)
        persist(context, stored.rules.filterNot {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized &&
                it.ruleId.trim().uppercase(Locale.ROOT) == normalizedRuleId
        })
    }

    internal fun decodeVerified(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val rules = mutableListOf<ConventionProvidentContributionV2.Rule>()
        var malformed = false
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val rule = obj?.let(::decode)?.takeIf(::acceptsVerifiedRule)
            if (rule == null) {
                malformed = true
            } else {
                rules += rule
            }
        }
        if (hasDuplicateRuleId(rules)) malformed = true
        return ReadResult(
            rules = rules,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun persist(context: Context, rules: List<ConventionProvidentContributionV2.Rule>) {
        check(acceptsVerifiedPackage(rules)) {
            "KALI cotisations prévoyance : historique local invalide ou ambigu."
        }
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionProvidentContributionV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.classification.label() }
                .thenBy { it.professionalStatus.orEmpty() }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_VERIFIED, array.toString()).commit()
        }.getOrDefault(false)
        check(saved) { "KALI cotisations prévoyance : stockage local des règles impossible." }
    }

    private fun hasDuplicateRuleId(rules: List<ConventionProvidentContributionV2.Rule>): Boolean {
        val seen = mutableSetOf<String>()
        return rules.any { rule ->
            val key = listOf(
                ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc),
                rule.ruleId.trim().uppercase(Locale.ROOT)
            ).joinToString("#")
            !seen.add(key)
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
                        .put("minimumTotalRate", band.minimumTotalRate)
                        .put("minimumEmployerRate", band.minimumEmployerRate)
                        .put("allocationRule", band.allocationRule.name)
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
                        val allocationRule = text(band, "allocationRule")
                            ?.let { ConventionProvidentContributionV2.AllocationRule.valueOf(it) }
                            ?: ConventionProvidentContributionV2.AllocationRule.EXACT
                        add(
                            ConventionProvidentContributionV2.Band(
                                label = band.getString("label"),
                                lowerCeilingMultiple = band.getDouble("lowerCeilingMultiple"),
                                upperCeilingMultiple = number(band, "upperCeilingMultiple"),
                                employeeRate = band.getDouble("employeeRate"),
                                employerRate = band.getDouble("employerRate"),
                                minimumTotalRate = number(band, "minimumTotalRate"),
                                minimumEmployerRate = number(band, "minimumEmployerRate"),
                                allocationRule = allocationRule
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

    private fun number(obj: JSONObject, key: String): Double? =
        if (!obj.has(key) || obj.isNull(key)) null else obj.optDouble(key).takeIf { it.isFinite() }
}
