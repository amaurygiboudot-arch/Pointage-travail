package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Lie les règles locales de paniers au dernier audit officiel complet qui les a validées.
 *
 * La couverture juridique et le cache de règles sont volontairement séparés. Ce marqueur empêche
 * donc une règle ancienne, absente d'un nouvel audit complet, d'être réactivée silencieusement.
 */
object MealBasketAuditTrustStoreV2 {
    private const val PREFS = "horatrack_v2_meal_basket_audit_trust"

    enum class State { COMPLETE, INCOMPLETE }

    data class Record(
        val state: State,
        val fingerprints: Set<String>,
        val sourceIds: Set<String>,
        val checkedAtMs: Long
    ) {
        fun structurallyValid(): Boolean = checkedAtMs > 0L &&
            fingerprints.none { it.isBlank() } && sourceIds.none { it.isBlank() }
    }

    fun markAcco(
        context: Context,
        companyId: String,
        profile: ConventionLegalProfileV2,
        state: State,
        verifiedAgreementIds: Set<String> = emptySet(),
        fingerprints: Set<String> = emptySet()
    ): Boolean {
        val key = accoKey(companyId, profile) ?: return false
        val normalizedIds = verifiedAgreementIds.mapTo(linkedSetOf()) { it.trim().uppercase() }
            .filterTo(linkedSetOf()) { it.matches(Regex("^ACCOTEXT\\d+$")) }
        return persist(context, key, Record(
            state = state,
            fingerprints = fingerprints.filterTo(linkedSetOf()) { it.isNotBlank() },
            sourceIds = normalizedIds,
            checkedAtMs = System.currentTimeMillis().coerceAtLeast(1L)
        ))
    }

    fun acco(
        context: Context,
        companyId: String,
        profile: ConventionLegalProfileV2
    ): Record? = accoKey(companyId, profile)?.let { load(context, it) }

    fun markKali(
        context: Context,
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        state: State,
        fingerprints: Set<String> = emptySet()
    ): Boolean {
        val key = kaliKey(profile, referenceDate) ?: return false
        return persist(context, key, Record(
            state = state,
            fingerprints = fingerprints.filterTo(linkedSetOf()) { it.isNotBlank() },
            sourceIds = emptySet(),
            checkedAtMs = System.currentTimeMillis().coerceAtLeast(1L)
        ))
    }

    fun kali(
        context: Context,
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate
    ): Record? = kaliKey(profile, referenceDate)?.let { load(context, it) }

    fun accoFingerprint(rule: OfficialAccoMealBasketParserV2.Rule): String = rule.fingerprint

    fun kaliFingerprint(rule: ConventionMealBasketV2.Rule): String = listOf(
        "KALI_MEAL",
        ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc),
        rule.ruleId,
        rule.benefitId,
        rule.effectiveFrom.toString(),
        rule.effectiveTo?.toString().orEmpty(),
        rule.classification.normalized().label(),
        rule.professionalStatus?.trim()?.uppercase().orEmpty(),
        rule.territoryCodes.sorted().joinToString(","),
        rule.excludedEmployments.sorted().joinToString(","),
        rule.deliveryMode.name,
        OfficialAccoMealBasketParserV2.amountFingerprint(rule.amountFormula),
        OfficialAccoMealBasketParserV2.eligibilityFingerprint(rule.eligibilityAnyOf),
        rule.blockers.sortedBy { it.name }.joinToString(",") { it.name },
        rule.countingUnit.name,
        rule.maxAwardsPerCalendarDay.toString(),
        rule.conventionScopeKey,
        rule.evidenceArticleIds.sorted().joinToString(","),
        rule.extensionStatus.name,
        rule.extensionEffectiveFrom?.toString().orEmpty()
    ).joinToString("|")

    private fun accoKey(companyId: String, profile: ConventionLegalProfileV2): String? {
        val siret = profile.siret.filter(Char::isDigit)
        val status = profile.professionalStatus?.trim()?.uppercase().orEmpty()
        if (companyId.isBlank() || siret.length != 14 || profile.classification.isEmpty() || status.isBlank()) return null
        return listOf("ACCO", companyId, siret, profile.classification.normalized().label(), status).joinToString("|")
    }

    private fun kaliKey(profile: ConventionLegalProfileV2, referenceDate: LocalDate): String? {
        val idcc = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)
        val status = profile.professionalStatus?.trim()?.uppercase().orEmpty()
        if (idcc.isBlank() || profile.classification.isEmpty() || status.isBlank()) return null
        return listOf(
            "KALI",
            idcc,
            "%04d-%02d".format(referenceDate.year, referenceDate.monthValue),
            profile.classification.normalized().label(),
            status
        ).joinToString("|")
    }

    private fun persist(context: Context, key: String, record: Record): Boolean {
        if (!record.structurallyValid()) return false
        val json = JSONObject()
            .put("state", record.state.name)
            .put("fingerprints", JSONArray(record.fingerprints.sorted()))
            .put("sourceIds", JSONArray(record.sourceIds.sorted()))
            .put("checkedAtMs", record.checkedAtMs)
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, json.toString()).commit()
    }

    private fun load(context: Context, key: String): Record? {
        val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Record(
                state = State.valueOf(json.getString("state")),
                fingerprints = strings(json.optJSONArray("fingerprints")),
                sourceIds = strings(json.optJSONArray("sourceIds")),
                checkedAtMs = json.getLong("checkedAtMs")
            ).takeIf { it.structurallyValid() }
        }.getOrNull()
    }

    private fun strings(array: JSONArray?): Set<String> = buildSet {
        if (array == null) return@buildSet
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
