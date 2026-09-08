package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/**
 * Cache LOCAL des cotisations de prévoyance d'entreprise structurées depuis ACCO.
 *
 * Aucune donnée n'est envoyée à Firebase. Le stockage est rattaché à l'entreprise locale et chaque
 * règle conserve son SIRET exact : un changement de SIRET invalide automatiquement les anciennes
 * preuves pour le calcul. L'équivalence des garanties L2253-1 n'est volontairement pas stockée ici.
 */
object V2CompanyProvidentContributionStore {
    internal const val KEY = "company_provident_contribution_rules_v2"

    fun rules(
        context: Context,
        companyId: String
    ): List<OfficialAccoProvidentContributionParserV2.Rule> {
        val profile = ConventionLegalProfileV2.load(context, companyId) ?: return emptyList()
        val expectedSiret = profile.siret.filter(Char::isDigit).takeIf { it.length == 14 } ?: return emptyList()
        return decodeRules(
            SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]") ?: "[]"
        ).filter { acceptsVerifiedRule(it, expectedSiret) }
    }

    fun saveVerified(
        context: Context,
        companyId: String,
        rule: OfficialAccoProvidentContributionParserV2.Rule
    ): Boolean {
        val profile = ConventionLegalProfileV2.load(context, companyId) ?: return false
        val expectedSiret = profile.siret.filter(Char::isDigit).takeIf { it.length == 14 } ?: return false
        if (!acceptsVerifiedRule(rule, expectedSiret)) return false

        val current = rules(context, companyId).toMutableList()
        current.removeAll { sameScope(it, rule) }
        current += normalized(rule)
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, encodeRules(current))
            .commit()
    }

    fun delete(
        context: Context,
        companyId: String,
        agreementId: String,
        fingerprint: String
    ): Boolean {
        val current = rules(context, companyId)
        val updated = current.filterNot {
            it.agreementId.equals(agreementId.trim(), ignoreCase = true) && it.fingerprint == fingerprint
        }
        if (updated.size == current.size) return false
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, encodeRules(updated))
            .commit()
    }

    internal fun acceptsVerifiedRule(
        rule: OfficialAccoProvidentContributionParserV2.Rule,
        expectedSiret: String
    ): Boolean {
        val normalizedSiret = expectedSiret.filter(Char::isDigit)
        if (normalizedSiret.length != 14 || rule.siret.filter(Char::isDigit) != normalizedSiret) return false
        if (!rule.agreementId.trim().uppercase(Locale.ROOT).matches(Regex("^ACCOTEXT\\d+$"))) return false
        if (rule.classification.isEmpty()) return false
        if (rule.professionalStatus.trim().uppercase(Locale.ROOT) !in setOf("CADRE", "NON_CADRE")) return false
        if (rule.effectiveTo != null && rule.effectiveTo.isBefore(rule.effectiveFrom)) return false
        if (rule.minimumSeniorityMonths !in 0..600) return false
        if (!rule.employeeRate.isFinite() || !rule.employerRate.isFinite()) return false
        if (rule.employeeRate !in 0.0..1.0 || rule.employerRate !in 0.0..1.0) return false
        if (rule.employeeRate + rule.employerRate <= 0.0) return false
        if (rule.evidenceExcerpt.isBlank()) return false
        return true
    }

    internal fun encodeRules(rules: List<OfficialAccoProvidentContributionParserV2.Rule>): String {
        val array = JSONArray()
        rules.sortedWith(
            compareBy<OfficialAccoProvidentContributionParserV2.Rule> { it.siret }
                .thenBy { it.agreementId }
                .thenBy { it.effectiveFrom }
                .thenBy { it.classification.label() }
                .thenBy { it.professionalStatus }
        ).forEach { rule ->
            array.put(
                JSONObject()
                    .put("agreementId", rule.agreementId)
                    .put("siret", rule.siret)
                    .put("effectiveFrom", rule.effectiveFrom.toString())
                    .put("effectiveTo", rule.effectiveTo?.toString())
                    .put("classification", encodeClassification(rule.classification))
                    .put("professionalStatus", rule.professionalStatus)
                    .put("minimumSeniorityMonths", rule.minimumSeniorityMonths)
                    .put("basis", rule.basis.name)
                    .put("employeeRate", rule.employeeRate)
                    .put("employerRate", rule.employerRate)
                    .put("evidenceExcerpt", rule.evidenceExcerpt)
            )
        }
        return array.toString()
    }

    internal fun decodeRules(raw: String): List<OfficialAccoProvidentContributionParserV2.Rule> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decodeRule(array.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
    }

    private fun decodeRule(obj: JSONObject): OfficialAccoProvidentContributionParserV2.Rule? = runCatching {
        OfficialAccoProvidentContributionParserV2.Rule(
            agreementId = obj.getString("agreementId").trim().uppercase(Locale.ROOT),
            siret = obj.getString("siret").filter(Char::isDigit),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = text(obj, "effectiveTo")?.let(LocalDate::parse),
            classification = decodeClassification(obj.getJSONObject("classification")),
            professionalStatus = obj.getString("professionalStatus").trim().uppercase(Locale.ROOT),
            minimumSeniorityMonths = obj.getInt("minimumSeniorityMonths"),
            basis = OfficialAccoProvidentContributionParserV2.Basis.valueOf(obj.getString("basis")),
            employeeRate = obj.getDouble("employeeRate"),
            employerRate = obj.getDouble("employerRate"),
            evidenceExcerpt = obj.getString("evidenceExcerpt")
        )
    }.getOrNull()

    private fun normalized(
        rule: OfficialAccoProvidentContributionParserV2.Rule
    ): OfficialAccoProvidentContributionParserV2.Rule = rule.copy(
        agreementId = rule.agreementId.trim().uppercase(Locale.ROOT),
        siret = rule.siret.filter(Char::isDigit),
        professionalStatus = rule.professionalStatus.trim().uppercase(Locale.ROOT)
    )

    private fun sameScope(
        left: OfficialAccoProvidentContributionParserV2.Rule,
        right: OfficialAccoProvidentContributionParserV2.Rule
    ): Boolean =
        left.agreementId.equals(right.agreementId, ignoreCase = true) &&
            left.siret.filter(Char::isDigit) == right.siret.filter(Char::isDigit) &&
            left.effectiveFrom == right.effectiveFrom &&
            left.effectiveTo == right.effectiveTo &&
            left.classification == right.classification &&
            left.professionalStatus.equals(right.professionalStatus, ignoreCase = true) &&
            left.minimumSeniorityMonths == right.minimumSeniorityMonths &&
            left.basis == right.basis

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
