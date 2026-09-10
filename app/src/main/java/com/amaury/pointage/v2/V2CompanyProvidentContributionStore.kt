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
    private const val STORAGE_WARNING =
        "ACCO cotisations prévoyance : stockage local des règles d'entreprise incohérent ; aucune règle ni absence d'accord ne peut être déduite de ce stockage."

    data class ReadResult(
        val rules: List<OfficialAccoProvidentContributionParserV2.Rule>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun readVerified(context: Context, companyId: String): ReadResult {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return ReadResult(
                emptyList(),
                false,
                listOf("ACCO cotisations prévoyance : profil juridique local introuvable ; SIRET exact requis.")
            )
        val expectedSiret = profile.siret.filter(Char::isDigit).takeIf { it.length == 14 }
            ?: return ReadResult(
                emptyList(),
                false,
                listOf("ACCO cotisations prévoyance : SIRET local exact requis avant lecture des règles d'entreprise.")
            )
        val stored = readStored(context, companyId)
        if (!stored.reliable) return stored
        return ReadResult(
            rules = stored.rules.filter { acceptsVerifiedRule(it, expectedSiret) },
            reliable = true,
            warnings = emptyList()
        )
    }

    fun rules(
        context: Context,
        companyId: String
    ): List<OfficialAccoProvidentContributionParserV2.Rule> {
        val stored = readVerified(context, companyId)
        check(stored.reliable) { stored.warnings.firstOrNull() ?: STORAGE_WARNING }
        return stored.rules
    }

    fun saveVerified(
        context: Context,
        companyId: String,
        rule: OfficialAccoProvidentContributionParserV2.Rule
    ): Boolean {
        val profile = ConventionLegalProfileV2.load(context, companyId) ?: return false
        val expectedSiret = profile.siret.filter(Char::isDigit).takeIf { it.length == 14 } ?: return false
        if (!acceptsVerifiedRule(rule, expectedSiret)) return false

        val stored = readStored(context, companyId)
        if (!stored.reliable) return false
        val current = stored.rules.toMutableList()
        current.removeAll { sameLegalIdentity(it, rule) }
        current += normalized(rule)
        if (!acceptsVerifiedPackage(current)) return false
        return persist(context, companyId, current)
    }

    fun delete(
        context: Context,
        companyId: String,
        agreementId: String,
        fingerprint: String
    ): Boolean {
        val stored = readStored(context, companyId)
        if (!stored.reliable) return false
        val current = stored.rules
        val updated = current.filterNot {
            it.agreementId.equals(agreementId.trim(), ignoreCase = true) && it.fingerprint == fingerprint
        }
        if (updated.size == current.size) return false
        return persist(context, companyId, updated)
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

    private fun acceptsStoredRule(rule: OfficialAccoProvidentContributionParserV2.Rule): Boolean {
        val ownSiret = rule.siret.filter(Char::isDigit)
        return ownSiret.length == 14 && acceptsVerifiedRule(rule, ownSiret)
    }

    internal fun acceptsVerifiedPackage(rules: List<OfficialAccoProvidentContributionParserV2.Rule>): Boolean =
        rules.all(::acceptsStoredRule) && !hasDuplicateLegalIdentity(rules)

    /**
     * Identité stable de la preuve juridique. Les valeurs calculables et l'ancienneté ne font pas
     * partie de cette identité : si une réanalyse du même ACCOTEXT/profile/période les révise,
     * l'ancienne variante doit être remplacée plutôt que conservée en parallèle.
     */
    internal fun sameLegalIdentity(
        left: OfficialAccoProvidentContributionParserV2.Rule,
        right: OfficialAccoProvidentContributionParserV2.Rule
    ): Boolean =
        left.agreementId.equals(right.agreementId, ignoreCase = true) &&
            left.siret.filter(Char::isDigit) == right.siret.filter(Char::isDigit) &&
            left.effectiveFrom == right.effectiveFrom &&
            left.effectiveTo == right.effectiveTo &&
            left.classification == right.classification &&
            left.professionalStatus.equals(right.professionalStatus, ignoreCase = true) &&
            left.basis == right.basis

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

    /** Compatibilité des tests/outils internes : la fiabilité doit être lue via [decodeVerified]. */
    internal fun decodeRules(raw: String): List<OfficialAccoProvidentContributionParserV2.Rule> =
        decodeVerified(raw).rules

    internal fun decodeVerified(raw: String): ReadResult {
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        val rules = mutableListOf<OfficialAccoProvidentContributionParserV2.Rule>()
        var malformed = false
        for (index in 0 until array.length()) {
            val obj = array.opt(index) as? JSONObject
            val rule = obj?.let(::decodeRule)?.takeIf(::acceptsStoredRule)
            if (rule == null) malformed = true else rules += rule
        }
        if (hasDuplicateLegalIdentity(rules)) malformed = true
        return ReadResult(
            rules = rules,
            reliable = !malformed,
            warnings = if (malformed) listOf(STORAGE_WARNING) else emptyList()
        )
    }

    private fun readStored(context: Context, companyId: String): ReadResult {
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        if (!prefs.contains(KEY)) return ReadResult(emptyList(), true, emptyList())
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
            ?: return ReadResult(emptyList(), false, listOf(STORAGE_WARNING))
        return decodeVerified(raw)
    }

    private fun persist(
        context: Context,
        companyId: String,
        rules: List<OfficialAccoProvidentContributionParserV2.Rule>
    ): Boolean {
        if (!acceptsVerifiedPackage(rules)) return false
        return SalaryCompanyStore.prefs(context, companyId)
            .edit()
            .putString(KEY, encodeRules(rules))
            .commit()
    }

    private fun hasDuplicateLegalIdentity(
        rules: List<OfficialAccoProvidentContributionParserV2.Rule>
    ): Boolean = rules.indices.any { leftIndex ->
        ((leftIndex + 1) until rules.size).any { rightIndex ->
            sameLegalIdentity(rules[leftIndex], rules[rightIndex])
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
