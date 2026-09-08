package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.CompanyProvidentBenefitV2
import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

/** Cache LOCAL des garanties de prévoyance d'entreprise structurées depuis ACCO. */
object V2CompanyProvidentBenefitStore {
    internal const val KEY = "company_provident_benefit_rules_v2"

    fun rules(context: Context, companyId: String): List<CompanyProvidentBenefitV2.Rule> {
        val profile = ConventionLegalProfileV2.load(context, companyId) ?: return emptyList()
        val expectedSiret = profile.siret.filter(Char::isDigit).takeIf { it.length == 14 } ?: return emptyList()
        return decodeRules(SalaryCompanyStore.prefs(context, companyId).getString(KEY, "[]") ?: "[]")
            .filter { acceptsVerifiedRule(it, expectedSiret) }
    }

    fun saveVerified(context: Context, companyId: String, rule: CompanyProvidentBenefitV2.Rule): Boolean {
        val profile = ConventionLegalProfileV2.load(context, companyId) ?: return false
        val expectedSiret = profile.siret.filter(Char::isDigit).takeIf { it.length == 14 } ?: return false
        if (!acceptsVerifiedRule(rule, expectedSiret)) return false
        val current = rules(context, companyId).toMutableList()
        current.removeAll { sameLegalIdentity(it, rule) }
        current += normalized(rule)
        return SalaryCompanyStore.prefs(context, companyId).edit()
            .putString(KEY, encodeRules(current))
            .commit()
    }

    internal fun acceptsVerifiedRule(rule: CompanyProvidentBenefitV2.Rule, expectedSiret: String): Boolean =
        rule.structurallyValid() &&
            expectedSiret.filter(Char::isDigit).let { it.length == 14 && rule.siret.filter(Char::isDigit) == it }

    internal fun sameLegalIdentity(left: CompanyProvidentBenefitV2.Rule, right: CompanyProvidentBenefitV2.Rule): Boolean =
        left.agreementId.equals(right.agreementId, ignoreCase = true) &&
            left.siret.filter(Char::isDigit) == right.siret.filter(Char::isDigit) &&
            left.effectiveFrom == right.effectiveFrom &&
            left.effectiveTo == right.effectiveTo &&
            left.classification == right.classification &&
            left.professionalStatus.equals(right.professionalStatus, ignoreCase = true) &&
            left.guarantee.family == right.guarantee.family &&
            left.guarantee.invalidityCategory == right.guarantee.invalidityCategory

    internal fun encodeRules(rules: List<CompanyProvidentBenefitV2.Rule>): String {
        val array = JSONArray()
        rules.sortedWith(compareBy<CompanyProvidentBenefitV2.Rule> { it.agreementId }
            .thenBy { it.effectiveFrom }
            .thenBy { it.guarantee.family.name }
            .thenBy { it.guarantee.invalidityCategory ?: 0 })
            .forEach { rule -> array.put(encodeRule(rule)) }
        return array.toString()
    }

    internal fun decodeRules(raw: String): List<CompanyProvidentBenefitV2.Rule> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                decodeRule(array.optJSONObject(index) ?: continue)?.let(::add)
            }
        }
    }

    private fun encodeRule(rule: CompanyProvidentBenefitV2.Rule): JSONObject = JSONObject()
        .put("agreementId", rule.agreementId)
        .put("siret", rule.siret)
        .put("effectiveFrom", rule.effectiveFrom.toString())
        .put("effectiveTo", rule.effectiveTo?.toString())
        .put("classification", encodeClassification(rule.classification))
        .put("professionalStatus", rule.professionalStatus)
        .put("minimumSeniorityMonths", rule.minimumSeniorityMonths)
        .put("guarantee", encodeGuarantee(rule.guarantee))
        .put("observedFamilies", JSONArray(rule.observedFamilies.map { it.name }))
        .put("packageComplete", rule.packageComplete)
        .put("evidenceExcerpt", rule.evidenceExcerpt)

    private fun decodeRule(obj: JSONObject): CompanyProvidentBenefitV2.Rule? = runCatching {
        val familiesArray = obj.getJSONArray("observedFamilies")
        val families = buildSet {
            for (index in 0 until familiesArray.length()) {
                add(ConventionProvidentBenefitV2.Family.valueOf(familiesArray.getString(index)))
            }
        }
        CompanyProvidentBenefitV2.Rule(
            agreementId = obj.getString("agreementId").trim().uppercase(Locale.ROOT),
            siret = obj.getString("siret").filter(Char::isDigit),
            effectiveFrom = LocalDate.parse(obj.getString("effectiveFrom")),
            effectiveTo = text(obj, "effectiveTo")?.let(LocalDate::parse),
            classification = decodeClassification(obj.getJSONObject("classification")),
            professionalStatus = obj.getString("professionalStatus").trim().uppercase(Locale.ROOT),
            minimumSeniorityMonths = obj.getInt("minimumSeniorityMonths"),
            guarantee = decodeGuarantee(obj.getJSONObject("guarantee")),
            observedFamilies = families,
            packageComplete = obj.optBoolean("packageComplete", false),
            evidenceExcerpt = obj.getString("evidenceExcerpt")
        )
    }.getOrNull()

    private fun encodeGuarantee(value: CompanyProvidentBenefitV2.Guarantee): JSONObject = JSONObject()
        .put("family", value.family.name)
        .put("label", value.label)
        .put("basis", value.formula.basis.name)
        .put("coefficient", value.formula.coefficient)
        .put("fixedAmount", value.formula.fixedAmount)
        .put("waitingPeriodDays", value.waitingPeriodDays)
        .put("maximumDurationDays", value.maximumDurationDays)
        .put("invalidityCategory", value.invalidityCategory)
        .put("socialSecurityTreatment", value.socialSecurityTreatment.name)

    private fun decodeGuarantee(obj: JSONObject) = CompanyProvidentBenefitV2.Guarantee(
        family = ConventionProvidentBenefitV2.Family.valueOf(obj.getString("family")),
        label = obj.getString("label"),
        formula = ConventionProvidentBenefitV2.Formula(
            basis = ConventionProvidentBenefitV2.Basis.valueOf(obj.getString("basis")),
            coefficient = number(obj, "coefficient"),
            fixedAmount = number(obj, "fixedAmount")
        ),
        waitingPeriodDays = integer(obj, "waitingPeriodDays"),
        maximumDurationDays = integer(obj, "maximumDurationDays"),
        invalidityCategory = integer(obj, "invalidityCategory"),
        socialSecurityTreatment = ConventionProvidentBenefitV2.SocialSecurityTreatment.valueOf(
            obj.getString("socialSecurityTreatment")
        )
    )

    private fun normalized(rule: CompanyProvidentBenefitV2.Rule) = rule.copy(
        agreementId = rule.agreementId.trim().uppercase(Locale.ROOT),
        siret = rule.siret.filter(Char::isDigit),
        professionalStatus = rule.professionalStatus.trim().uppercase(Locale.ROOT)
    )

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

    private fun text(obj: JSONObject, key: String): String? = obj.optString(key).takeIf { it.isNotBlank() && it != "null" }
    private fun number(obj: JSONObject, key: String): Double? = if (obj.isNull(key)) null else obj.optDouble(key).takeIf { it.isFinite() }
    private fun integer(obj: JSONObject, key: String): Int? = if (obj.isNull(key)) null else obj.optInt(key)
}
