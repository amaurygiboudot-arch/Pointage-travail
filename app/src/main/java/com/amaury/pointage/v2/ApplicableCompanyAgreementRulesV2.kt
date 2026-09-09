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

    data class Snapshot(
        val rules: List<CompanyAgreementRuleStoreV2.StoredCandidate>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        context: Context,
        companyId: String,
        referenceDate: LocalDate
    ): Snapshot = resolve(CompanyAgreementRuleStoreV2.read(context, companyId), referenceDate)

    internal fun resolve(
        stored: CompanyAgreementRuleStoreV2.ReadResult,
        referenceDate: LocalDate
    ): Snapshot {
        if (!stored.reliable) {
            return Snapshot(
                rules = emptyList(),
                reliable = false,
                warnings = stored.warnings.ifEmpty {
                    listOf("Règles ACCO : stockage local non fiable ; calcul d'entreprise bloqué.")
                }
            )
        }

        var incompleteVerifiedRule = false
        val applicable = stored.records.mapNotNull { rule ->
            if (!rule.verified) return@mapNotNull null
            if (rule.scope.isNullOrBlank()) {
                incompleteVerifiedRule = true
                return@mapNotNull null
            }

            val from = parseDate(rule.effectiveFrom)
            if (from == null) {
                incompleteVerifiedRule = true
                return@mapNotNull null
            }
            val to = rule.effectiveTo?.takeIf { it.isNotBlank() }?.let(::parseDate)
                ?: if (rule.effectiveTo.isNullOrBlank()) null else {
                    incompleteVerifiedRule = true
                    return@mapNotNull null
                }
            if (to != null && to.isBefore(from)) {
                incompleteVerifiedRule = true
                return@mapNotNull null
            }

            rule.takeIf {
                !referenceDate.isBefore(from) && (to == null || !referenceDate.isAfter(to))
            }
        }

        return Snapshot(
            rules = applicable,
            reliable = !incompleteVerifiedRule,
            warnings = if (incompleteVerifiedRule) {
                listOf(
                    "Règles ACCO : au moins une règle vérifiée n'a pas une période ou un champ d'application exploitable ; repli juridique automatique bloqué."
                )
            } else {
                emptyList()
            }
        )
    }

    fun list(
        context: Context,
        companyId: String,
        referenceDate: LocalDate
    ): List<CompanyAgreementRuleStoreV2.StoredCandidate> = resolve(context, companyId, referenceDate).rules

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
