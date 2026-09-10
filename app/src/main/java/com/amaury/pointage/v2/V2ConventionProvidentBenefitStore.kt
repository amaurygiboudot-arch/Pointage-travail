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
    private const val STORAGE_WARNING =
        "KALI garanties prévoyance : stockage local des règles vérifiées incohérent ; aucune garantie conventionnelle ne peut être déduite de ce stockage."

    data class ReadResult(
        val rules: List<ConventionProvidentBenefitV2.Rule>,
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

    fun rules(context: Context): List<ConventionProvidentBenefitV2.Rule> {
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.rules
    }

    fun rules(context: Context, idcc: String): List<ConventionProvidentBenefitV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.rules.filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    internal fun acceptsVerifiedRule(rule: ConventionProvidentBenefitV2.Rule): Boolean =
        rule.structurallyValid() &&
            rule.conventionScopeKey.trim().uppercase(Locale.ROOT).matches(Regex("^KALITEXT\\d+$"))

    internal fun acceptsVerifiedPackage(rules: List<ConventionProvidentBenefitV2.Rule>): Boolean =
        rules.all(::acceptsVerifiedRule) && !hasDuplicateStoredIdentity(rules)

    /**
     * Identité juridique stable d'une règle KALI.
     *
     * Le contenu calculable (formule, franchise, ancienneté...) peut être corrigé lors d'un audit
     * ultérieur sans que la source juridique ait changé. Ces valeurs ne participent donc pas à
     * l'identité. En revanche, le texte/article KALI, la famille, le profil et la période doivent
     * rester strictement identiques avant qu'une variante précédente puisse être remplacée.
     */
    internal fun sameLegalIdentity(
        left: ConventionProvidentBenefitV2.Rule,
        right: ConventionProvidentBenefitV2.Rule
    ): Boolean = legalIdentity(left) == legalIdentity(right)

    fun saveVerified(context: Context, rule: ConventionProvidentBenefitV2.Rule) {
        require(acceptsVerifiedRule(rule)) { "Règle de garanties prévoyance invalide ou sans périmètre KALI exact" }
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val normalizedRule = rule.copy(
            idcc = normalized,
            professionalStatus = rule.professionalStatus?.trim()?.uppercase(Locale.ROOT),
            conventionScopeKey = rule.conventionScopeKey.trim().uppercase(Locale.ROOT)
        )
        val current = stored.rules.toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized &&
                (it.ruleId == normalizedRule.ruleId || sameLegalIdentity(it, normalizedRule))
        }
        current += normalizedRule
        persist(context, current)
    }

    fun delete(context: Context, idcc: String, ruleId: String) {
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        persist(context, stored.rules.filterNot {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == ruleId
        })
    }

    private fun legalIdentity(rule: ConventionProvidentBenefitV2.Rule): String {
        val guarantees = rule.guarantees.map { guarantee ->
            listOf(
                guarantee.family.name,
                guarantee.invalidityCategory?.toString().orEmpty(),
                guarantee.evidenceArticleIds
                    .map { it.trim().uppercase(Locale.ROOT) }
                    .sorted()
                    .joinToString(",")
            ).joinToString("|")
        }.sorted().joinToString(";")
        return listOf(
            ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc),
            rule.conventionScopeKey.trim().uppercase(Locale.ROOT),
            rule.effectiveFrom.toString(),
            rule.effectiveTo?.toString().orEmpty(),
            rule.classification.label(),
            rule.professionalStatus?.trim()?.uppercase(Locale.ROOT).orEmpty(),
            rule.aniCategories.map { it.name }.sorted().joinToString(","),
            guarantees
        ).joinToString("#")
    }

    internal fun decodeVerified(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val rules = mutableListOf<ConventionProvidentBenefitV2.Rule>()
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
        if (hasDuplicateStoredIdentity(rules)) malformed = true
        return ReadResult(
            rules = rules,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun persist(context: Context, rules: List<ConventionProvidentBenefitV2.Rule>) {
        check(acceptsVerifiedPackage(rules)) {
            "KALI garanties prévoyance : historique local invalide ou ambigu."
        }
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionProvidentBenefitV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.classification.label() }
                .thenBy { it.professionalStatus.orEmpty() }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_VERIFIED, array.toString()).commit()
        }.getOrDefault(false)
        check(saved) { "KALI garanties prévoyance : stockage local des règles impossible." }
    }

    private fun hasDuplicateStoredIdentity(rules: List<ConventionProvidentBenefitV2.Rule>): Boolean =
        rules.indices.any { leftIndex ->
            ((leftIndex + 1) until rules.size).any { rightIndex ->
                val left = rules[leftIndex]
                val right = rules[rightIndex]
                val sameIdcc = ConventionMinimumSalaryV2.normalizeIdcc(left.idcc) ==
                    ConventionMinimumSalaryV2.normalizeIdcc(right.idcc)
                sameIdcc && (left.ruleId == right.ruleId || sameLegalIdentity(left, right))
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
