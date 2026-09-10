package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * Stockage LOCAL des catégories ANI dont la preuve officielle a déjà été structurée.
 *
 * Aucune fiche salarié n'est envoyée dans Firestore par ce composant. Une absence de règle
 * dans ce store ne prouve jamais une absence de droit : seule ConventionMatterCoverageV2
 * peut porter une conclusion de couverture, et uniquement après audit officiel complet.
 *
 * La preuve KALI et la preuve APEC restent séparées. Un agrément APEC ne devient applicable
 * que si son périmètre, sa classification et sa catégorie correspondent exactement à la règle KALI.
 */
object V2ConventionProtectionCategoryStore {
    private const val PREFS = "horatrack_v2_convention_protection_category_rules"
    private const val KEY_VERIFIED = "verified_rules"
    private const val STORAGE_WARNING =
        "KALI catégorie ANI : stockage local des preuves KALI/APEC incohérent ; aucune catégorie de prévoyance ne peut être déduite de ce stockage."

    data class ReadResult(
        val rules: List<ConventionProtectionCategoryV2.Rule>,
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

    fun rules(context: Context): List<ConventionProtectionCategoryV2.Rule> {
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.rules
    }

    fun rules(context: Context, idcc: String): List<ConventionProtectionCategoryV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        return stored.rules.filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    /**
     * Une preuve persistable doit déjà avoir un KALITEXT parent exact. L'agrément APEC peut
     * rester à vérifier, mais une preuve KALI sans périmètre officiel ne rentre pas dans le store.
     */
    internal fun acceptsVerifiedRule(rule: ConventionProtectionCategoryV2.Rule): Boolean =
        rule.structurallyValid() &&
            rule.conventionScopeKey?.matches(Regex("^KALITEXT\\d+$")) == true &&
            rule.aniCategory != ProtectionCategoryV2.AniCategory.TO_CONFIRM &&
            rule.aniCategory != ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE

    internal fun acceptsVerifiedPackage(rules: List<ConventionProtectionCategoryV2.Rule>): Boolean =
        rules.all(::acceptsVerifiedRule) && !hasDuplicateRuleIds(rules)

    /**
     * Enregistre une preuve structurée. "Verified" ne signifie pas "applicable" :
     * le résolveur contrôle encore période, extension KALI et agrément APEC exact.
     */
    fun saveVerified(context: Context, rule: ConventionProtectionCategoryV2.Rule) {
        require(acceptsVerifiedRule(rule)) { "Règle de catégorie ANI non vérifiable, sans périmètre KALI exact ou incertaine" }

        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val normalizedStatus = rule.professionalStatus?.trim()?.uppercase(Locale.ROOT)
        val current = stored.rules.toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == rule.ruleId
        }
        current += rule.copy(idcc = normalized, professionalStatus = normalizedStatus)
        persist(context, current)
    }

    fun delete(context: Context, idcc: String, ruleId: String) {
        val stored = readVerified(context)
        check(stored.reliable) { STORAGE_WARNING }
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        persist(
            context,
            stored.rules.filterNot {
                ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == ruleId
            }
        )
    }

    internal fun decodeVerified(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val rules = mutableListOf<ConventionProtectionCategoryV2.Rule>()
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
        if (hasDuplicateRuleIds(rules)) malformed = true
        return ReadResult(
            rules = rules,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun persist(context: Context, rules: List<ConventionProtectionCategoryV2.Rule>) {
        check(acceptsVerifiedPackage(rules)) {
            "KALI catégorie ANI : historique local invalide ou ambigu."
        }
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionProtectionCategoryV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.classification.label() }
                .thenBy { it.professionalStatus.orEmpty() }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        val saved = runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_VERIFIED, array.toString())
                .commit()
        }.getOrDefault(false)
        check(saved) { "KALI catégorie ANI : stockage local des preuves impossible." }
    }

    private fun hasDuplicateRuleIds(rules: List<ConventionProtectionCategoryV2.Rule>): Boolean =
        rules.groupBy {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) to it.ruleId
        }.values.any { it.size > 1 }

    private fun encode(rule: ConventionProtectionCategoryV2.Rule): JSONObject = JSONObject()
        .put("idcc", ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc))
        .put("ruleId", rule.ruleId)
        .put("effectiveFrom", rule.effectiveFrom.toString())
        .put("effectiveTo", rule.effectiveTo?.toString())
        .put("classification", encodeClassification(rule.classification))
        .put("professionalStatus", rule.professionalStatus)
        .put("aniCategory", rule.aniCategory.name)
        .put("source", rule.source)
        .put("extensionStatus", rule.extensionStatus.name)
        .put("extensionEffectiveFrom", rule.extensionEffectiveFrom?.toString())
        .put("conventionScopeKey", rule.conventionScopeKey)
        .put("approvalStatus", rule.approvalStatus.name)
        .put("approvalEffectiveFrom", rule.approvalEffectiveFrom?.toString())
        .put("approvalSource", rule.approvalSource)
        .put("approvalScopeKey", rule.approvalScopeKey)
        .put("approvalClassification", rule.approvalClassification?.let(::encodeClassification))
        .put("approvalAniCategory", rule.approvalAniCategory?.name)

    private fun encodeClassification(value: ConventionClassificationV2): JSONObject = JSONObject()
        .put("coefficient", value.coefficient)
        .put("level", value.level)
        .put("echelon", value.echelon)
        .put("position", value.position)
        .put("group", value.group)
        .put("category", value.category)
        .put("employment", value.employment)

    private fun decode(obj: JSONObject): ConventionProtectionCategoryV2.Rule? = runCatching {
        val approvalStatus = obj.optString("approvalStatus")
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let(ConventionProtectionCategoryV2.ApprovalStatus::valueOf)
            ?: ConventionProtectionCategoryV2.ApprovalStatus.APEC_REQUIRED_UNVERIFIED
        ConventionProtectionCategoryV2.Rule(
            idcc = obj.getString("idcc"),
            ruleId = obj.getString("ruleId"),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = obj.optString("effectiveTo")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let(LocalDate::parse),
            classification = decodeClassification(obj.optJSONObject("classification") ?: JSONObject()),
            professionalStatus = obj.optString("professionalStatus")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.trim()
                ?.uppercase(Locale.ROOT),
            aniCategory = ProtectionCategoryV2.AniCategory.valueOf(obj.getString("aniCategory")),
            source = obj.getString("source"),
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.valueOf(obj.getString("extensionStatus")),
            extensionEffectiveFrom = obj.optString("extensionEffectiveFrom")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let(LocalDate::parse),
            conventionScopeKey = obj.optString("conventionScopeKey")
                .takeIf { it.isNotBlank() && it != "null" },
            approvalStatus = approvalStatus,
            approvalEffectiveFrom = obj.optString("approvalEffectiveFrom")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let(LocalDate::parse),
            approvalSource = obj.optString("approvalSource")
                .takeIf { it.isNotBlank() && it != "null" },
            approvalScopeKey = obj.optString("approvalScopeKey")
                .takeIf { it.isNotBlank() && it != "null" },
            approvalClassification = obj.optJSONObject("approvalClassification")?.let(::decodeClassification),
            approvalAniCategory = obj.optString("approvalAniCategory")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let(ProtectionCategoryV2.AniCategory::valueOf)
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
