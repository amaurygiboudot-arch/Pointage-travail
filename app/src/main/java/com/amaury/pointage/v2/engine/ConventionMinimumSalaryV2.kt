package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Résolution générique des minima conventionnels, indépendante d'une branche.
 *
 * Une règle n'est appliquée que si son IDCC, sa période, sa classification et
 * son statut d'extension permettent de prouver son applicabilité. HoraTrack ne
 * complète jamais une classification ou un statut d'extension manquant.
 */
object ConventionMinimumSalaryV2 {
    enum class Periodicity { HOURLY, MONTHLY, ANNUAL }
    enum class ExtensionStatus { EXTENDED, NOT_EXTENDED, UNKNOWN }

    data class Rule(
        val idcc: String,
        val ruleId: String,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        val classification: ConventionClassificationV2 = ConventionClassificationV2(),
        val amount: Double,
        val periodicity: Periodicity,
        val source: String,
        val extensionStatus: ExtensionStatus
    ) {
        fun structurallyValid(): Boolean = normalizeIdcc(idcc).isNotBlank() &&
            ruleId.isNotBlank() &&
            source.isNotBlank() &&
            amount.isFinite() &&
            amount > 0.0 &&
            (effectiveTo == null || !effectiveTo.isBefore(effectiveFrom))

        fun activeOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))

        fun applicableToCompany(companyApplicabilityConfirmed: Boolean): Boolean = when (extensionStatus) {
            ExtensionStatus.EXTENDED -> true
            ExtensionStatus.NOT_EXTENDED -> companyApplicabilityConfirmed
            ExtensionStatus.UNKNOWN -> false
        }
    }

    data class Result(
        val selected: Rule?,
        val latestKnown: Rule?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        rules: List<Rule>,
        idcc: String,
        date: LocalDate,
        classification: ConventionClassificationV2,
        companyApplicabilityConfirmed: Boolean = false
    ): Result {
        val normalizedIdcc = normalizeIdcc(idcc)
        if (normalizedIdcc.isBlank()) {
            return Result(null, null, false, listOf("Minimum conventionnel : IDCC manquant, aucun barème n'est inventé."))
        }

        val malformed = rules.filter { normalizeIdcc(it.idcc) == normalizedIdcc && !it.structurallyValid() }
        val validMatching = rules
            .asSequence()
            .filter { it.structurallyValid() }
            .filter { normalizeIdcc(it.idcc) == normalizedIdcc }
            .filter { it.activeOn(date) }
            .filter { classification.matches(it.classification) }
            .toList()

        if (validMatching.isEmpty()) {
            val warning = if (classification.isEmpty()) {
                "Minimum conventionnel IDCC $normalizedIdcc : classification non renseignée ou aucun barème confirmé ne correspond à la période."
            } else {
                "Minimum conventionnel IDCC $normalizedIdcc : aucun barème confirmé ne correspond à ${classification.label()} pour cette période."
            }
            return Result(
                selected = null,
                latestKnown = null,
                reliable = false,
                warnings = buildList {
                    add(warning)
                    if (malformed.isNotEmpty()) add("Minimum conventionnel : ${malformed.size} règle(s) enregistrée(s) sont incohérentes et ignorées.")
                }
            )
        }

        val latestKnown = chooseMostSpecificLatest(validMatching)
        val applicable = validMatching.filter { it.applicableToCompany(companyApplicabilityConfirmed) }
        val selectedCandidates = bestCandidates(applicable)
        val selected = selectedCandidates.singleOrNull()
        val conflict = selectedCandidates.size > 1 && selectedCandidates
            .map { Triple(it.amount, it.periodicity, it.ruleId) }
            .distinct()
            .size > 1

        val warnings = buildList {
            if (malformed.isNotEmpty()) {
                add("Minimum conventionnel : ${malformed.size} règle(s) enregistrée(s) sont incohérentes et ignorées.")
            }
            if (conflict) {
                add("Minimum conventionnel IDCC $normalizedIdcc : plusieurs barèmes applicables de même date et même précision se contredisent ; aucun minimum n'est appliqué automatiquement.")
            }
            if (selected == null && !conflict) {
                val status = latestKnown?.extensionStatus
                when (status) {
                    ExtensionStatus.NOT_EXTENDED -> add("Minimum conventionnel : le barème le plus récent correspondant à ${classification.label()} est non étendu et son applicabilité à l'entreprise n'est pas confirmée.")
                    ExtensionStatus.UNKNOWN -> add("Minimum conventionnel : le statut d'extension du barème le plus récent correspondant à ${classification.label()} n'est pas confirmé.")
                    else -> add("Minimum conventionnel : aucun barème applicable n'a pu être sélectionné sans hypothèse.")
                }
            }
            if (selected != null && latestKnown != null && latestKnown.effectiveFrom.isAfter(selected.effectiveFrom)) {
                val status = when (latestKnown.extensionStatus) {
                    ExtensionStatus.NOT_EXTENDED -> "non étendu"
                    ExtensionStatus.UNKNOWN -> "statut d'extension non confirmé"
                    ExtensionStatus.EXTENDED -> "étendu"
                }
                add("Minimum conventionnel : un barème plus récent existe depuis ${latestKnown.effectiveFrom} ($status), mais il n'est pas automatiquement substitué au dernier barème applicable vérifié.")
            }
        }.distinct()

        return Result(
            selected = if (conflict) null else selected,
            latestKnown = latestKnown,
            reliable = selected != null && !conflict,
            warnings = warnings
        )
    }

    private fun chooseMostSpecificLatest(rules: List<Rule>): Rule? = bestCandidates(rules).firstOrNull()

    private fun bestCandidates(rules: List<Rule>): List<Rule> {
        if (rules.isEmpty()) return emptyList()
        val latestDate = rules.maxOf { it.effectiveFrom }
        val latest = rules.filter { it.effectiveFrom == latestDate }
        val specificity = latest.maxOf { it.classification.specificity() }
        return latest.filter { it.classification.specificity() == specificity }
    }

    fun normalizeIdcc(value: String?): String = value.orEmpty()
        .filter(Char::isDigit)
        .trimStart('0')
        .ifBlank { "0" }
        .takeUnless { it == "0" }
        .orEmpty()
}
