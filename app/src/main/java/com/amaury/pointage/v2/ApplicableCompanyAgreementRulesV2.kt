package com.amaury.pointage.v2

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Barrière de sécurité entre les règles candidates et le moteur de paie.
 * Une règle n'est exposée que si elle est vérifiée, datée et dotée d'un champ d'application.
 */
object ApplicableCompanyAgreementRulesV2 {
    private val iso = DateTimeFormatter.ISO_LOCAL_DATE
    private val french = DateTimeFormatter.ofPattern("dd/MM/uuuu")

    fun list(
        context: Context,
        companyId: String,
        referenceDate: LocalDate
    ): List<CompanyAgreementRuleStoreV2.StoredCandidate> {
        return CompanyAgreementRuleStoreV2.list(context, companyId).filter { rule ->
            if (!rule.verified || rule.scope.isNullOrBlank()) return@filter false

            val from = parseDate(rule.effectiveFrom) ?: return@filter false
            val to = rule.effectiveTo?.takeIf { it.isNotBlank() }?.let(::parseDate)
                ?: if (rule.effectiveTo.isNullOrBlank()) null else return@filter false

            !referenceDate.isBefore(from) && (to == null || !referenceDate.isAfter(to))
        }
    }

    private fun parseDate(value: String?): LocalDate? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            LocalDate.parse(raw, iso)
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(raw, french)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
