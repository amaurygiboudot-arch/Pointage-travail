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
 * L'état d'agrément APEC est persisté séparément de la preuve KALI. Une règle KALI peut donc
 * être conservée comme preuve tout en restant non applicable tant que l'agrément n'est pas vérifié.
 */
object V2ConventionProtectionCategoryStore {
    private const val PREFS = "horatrack_v2_convention_protection_category_rules"
    private const val KEY_VERIFIED = "verified_rules"

    fun rules(context: Context): List<ConventionProtectionCategoryV2.Rule> = load(context)

    fun rules(context: Context, idcc: String): List<ConventionProtectionCategoryV2.Rule> {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        return load(context).filter { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized }
    }

    /** Garde pure utilisée aussi par les tests unitaires : aucune règle incertaine n'entre dans le store. */
    internal fun acceptsVerifiedRule(rule: ConventionProtectionCategoryV2.Rule): Boolean =
        rule.structurallyValid() &&
            rule.aniCategory != ProtectionCategoryV2.AniCategory.TO_CONFIRM &&
            rule.aniCategory != ProtectionCategoryV2.AniCategory.NO_CONVENTION_OVERRIDE

    /**
     * Enregistre une preuve structurée. "Verified" ne signifie pas "applicable" :
     * le résolveur contrôle encore période, extension KALI et agrément APEC.
     */
    fun saveVerified(context: Context, rule: ConventionProtectionCategoryV2.Rule) {
        require(acceptsVerifiedRule(rule)) { "Règle de catégorie ANI non vérifiable ou incertaine" }

        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)
        val normalizedStatus = rule.professionalStatus?.trim()?.uppercase(Locale.ROOT)
        val current = load(context).toMutableList()
        current.removeAll {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == rule.ruleId
        }
        current += rule.copy(idcc = normalized, professionalStatus = normalizedStatus)
        persist(context, current)
    }

    fun delete(context: Context, idcc: String, ruleId: String) {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        persist(
            context,
            load(context).filterNot {
                ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized && it.ruleId == ruleId
            }
        )
    }

    private fun persist(context: Context, rules: List<ConventionProtectionCategoryV2.Rule>) {
        val array = JSONArray()
        rules.sortedWith(
            compareBy<ConventionProtectionCategoryV2.Rule> { ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) }
                .thenBy { it.effectiveFrom }
                .thenBy { it.classification.label() }
                .thenBy { it.professionalStatus.orEmpty() }
                .thenBy { it.ruleId }
        ).forEach { array.put(encode(it)) }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VERIFIED, array.toString())
            .apply()
    }

    private fun load(context: Context): List<ConventionProtectionCategoryV2.Rule> {
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
        .put("approvalStatus", rule.approvalStatus.name)
        .put("approvalEffectiveFrom", rule.approvalEffectiveFrom?.toString())
        .put("approvalSource", rule.approvalSource)

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
            approvalStatus = approvalStatus,
            approvalEffectiveFrom = obj.optString("approvalEffectiveFrom")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let(LocalDate::parse),
            approvalSource = obj.optString("approvalSource")
                .takeIf { it.isNotBlank() && it != "null" }
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
