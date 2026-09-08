package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/** Cache LOCAL des garanties conventionnelles de prévoyance vérifiées dans KALI. */
object V2ConventionProvidentBenefitStore {
    private const val PREFS = "horatrack_v2_convention_provident_benefit_rules"
    private const val KEY_VERIFIED = "verified_rules"

    fun rules(context: Context): List<ConventionProvidentBenefitV2.Rule> = load(context)

    fun rules(context: Context, idcc: String): List<ConventionProvidentBenefitV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        return load(context).filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    internal fun acceptsVerifiedRule(rule: ConventionProvidentBenefitV2.Rule): Boolean =
        rule.structurallyValid() &&
            rule.conventionScopeKey.trim().uppercase(Locale.ROOT).matches(Regex("^KALITEXT\\d+$"))

    fun saveVerified(context: Context, rule: ConventionProvidentBenefitV2.Rule) {
        require(acceptsVerifiedRule(rule)) { "Règle de garanties prévoyance invalide ou sans périmètre KALI exact" }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val current = load(context).toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == rule.ruleId
        }
        current += rule.copy(
            idcc = normalized,
            professionalStatus = rule.professionalStatus?.trim()?.uppercase(Locale.ROOT),
            conventionScopeKey = rule.conventionScopeKey.trim().uppercase(Locale.ROOT)
        )
        persist(context, current)
    }

    fun delete(context: Context, idcc: String, ruleId: String) {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        persist(context, load(context).filterNot {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == ruleId
        })
    }

    private fun persist(context: Context, rules: List<ConventionProvidentBenefitV2.Rule>) {
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionProvidentBenefitV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.classification.label() }
                .thenBy { it.professionalStatus.orEmpty() }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_VERIFIED, array.toString()).apply()
    }

    private fun load(context: Context): List<ConventionProvidentBenefitV2.Rule> {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VERIFIED, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decode(array.optJSONObject(index) ?: continue)?.takeIf(::acceptsVerifiedRule)?.let(::add)
            }
        }
    }

    private fun encode(rule: ConventionProvidentBenefitV2.Rule): JSONObject {
        val ani = JSONArray()
        rule.aniCategories.sortedBy { it.name }.forEach { ani.put(it.name) }
        val guarantees = JSONArray()
        rule.guarantees.forEach { guarantee ->
            val evidence = JSONArray()
            guarantee.evidenceArticleIds.sorted().forEach { evidence.put(it) }
            guarantees.put(
                JSONObject()
                    .put("family", guarantee.family.name)
                    .put("label", guarantee.label)
                    .put("basis", guarantee.formula.basis.name)
                    .put("coefficient", guarantee.formula.coefficient)
                    .put("fixedAmount", guarantee.formula.fixedAmount)
                    .put("waitingPeriodDays", guarantee.waitingPeriodDays)
                    .put("maximumDurationDays", guarantee.maximumDurationDays)
                    .put("invalidityCategory", guarantee.invalidityCategory)
                    .put("socialSecurityTreatment", guarantee.socialSecurityTreatment.name)
                    .put("evidenceArticleIds", evidence)
            )
        }
        return JSONObject()
            .put("idcc", ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc))
            .put("ruleId", rule.ruleId)
            .put("effectiveFrom", rule.effectiveFrom.toString())
            .put("effectiveTo", rule.effectiveTo?.toString())
            .put("classification", encodeClassification(rule.classification))
            .put("professionalStatus", rule.professionalStatus)
            .put("aniCategories", ani)
            .put("minimumSeniorityMonths", rule.minimumSeniorityMonths)
            .put("guarantees", guarantees)
            .put("source", rule.source)
            .put("conventionScopeKey", rule.conventionScopeKey)
            .put("extensionStatus", rule.extensionStatus.name)
            .put("extensionEffectiveFrom", rule.extensionEffectiveFrom?.toString())
    }

    private fun decode(obj: JSONObject): ConventionProvidentBenefitV2.Rule? = runCatching {
        val aniJson = obj.optJSONArray("aniCategories") ?: JSONArray()
        val ani = buildSet {
            for (index in 0 until aniJson.length()) {
                add(ProtectionCategoryV2.AniCategory.valueOf(aniJson.getString(index)))
            }
        }
        val guaranteesJson = obj.getJSONArray("guarantees")
        val guarantees = buildList {
            for (index in 0 until guaranteesJson.length()) {
                val item = guaranteesJson.getJSONObject(index)
                val evidenceJson = item.getJSONArray("evidenceArticleIds")
                val evidence = buildSet {
                    for (evidenceIndex in 0 until evidenceJson.length()) add(evidenceJson.getString(evidenceIndex))
                }
                add(
                    ConventionProvidentBenefitV2.Guarantee(
                        family = ConventionProvidentBenefitV2.Family.valueOf(item.getString("family")),
                        label = item.getString("label"),
                        formula = ConventionProvidentBenefitV2.Formula(
                            basis = ConventionProvidentBenefitV2.Basis.valueOf(item.getString("basis")),
                            coefficient = if (item.isNull("coefficient")) null else item.getDouble("coefficient"),
                            fixedAmount = if (item.isNull("fixedAmount")) null else item.getDouble("fixedAmount")
                        ),
                        waitingPeriodDays = if (item.isNull("waitingPeriodDays")) null else item.getInt("waitingPeriodDays"),
                        maximumDurationDays = if (item.isNull("maximumDurationDays")) null else item.getInt("maximumDurationDays"),
                        invalidityCategory = if (item.isNull("invalidityCategory")) null else item.getInt("invalidityCategory"),
                        socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.valueOf(
                            item.getString("socialSecurityTreatment")
                        ),
                        evidenceArticleIds = evidence
                    )
                )
            }
        }
        ConventionProvidentBenefitV2.Rule(
            idcc = obj.getString("idcc"),
            ruleId = obj.getString("ruleId"),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = text(obj, "effectiveTo")?.let(LocalDate::parse),
            classification = decodeClassification(obj.optJSONObject("classification") ?: JSONObject()),
            professionalStatus = text(obj, "professionalStatus")?.uppercase(Locale.ROOT),
            aniCategories = ani,
            minimumSeniorityMonths = obj.getInt("minimumSeniorityMonths"),
            guarantees = guarantees,
            source = obj.getString("source"),
            conventionScopeKey = obj.getString("conventionScopeKey").uppercase(Locale.ROOT),
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
