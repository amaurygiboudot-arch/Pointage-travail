package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.util.Locale

/**
 * Preuve explicite qu'une source juridique prioritaire a été contrôlée pour une matière,
 * un champ d'application et une date donnés.
 *
 * L'absence de données locales ne vaut jamais preuve d'absence officielle. Pour les paniers repas,
 * la preuve est en plus liée à l'objet exact (jour, nuit, poste, hors domicile, etc.).
 */
object PayrollSourceKnowledgeProofV2 {
    enum class Matter {
        OVERTIME_RATE,
        PROVIDENT_CONTRIBUTION,
        MEAL_BASKET,
        SENIORITY_PREMIUM
    }

    enum class Outcome {
        NO_APPLICABLE_RULE,
        RULE_FOUND,
        INCONCLUSIVE
    }

    data class Proof(
        val source: PayrollLegalArbitratorV2.Source,
        val matter: Matter,
        val companyId: String? = null,
        val idcc: String? = null,
        /** Requis pour MEAL_BASKET afin d'isoler les objets MEAL_DAY, MEAL_NIGHT, etc. */
        val subjectKey: String? = null,
        val referenceFrom: LocalDate,
        val referenceTo: LocalDate,
        val officialCoverageThrough: LocalDate,
        val checkedAtMs: Long,
        val officialScopeId: String,
        val exhaustive: Boolean,
        val scopeConfirmed: Boolean,
        val outcome: Outcome
    ) {
        init {
            require(!referenceTo.isBefore(referenceFrom)) { "Période de preuve invalide" }
            require(checkedAtMs > 0L) { "Date de contrôle obligatoire" }
            require(officialScopeId.isNotBlank()) { "Empreinte du périmètre officiel obligatoire" }
            if (matter == Matter.MEAL_BASKET) {
                require(normalizeSubject(subjectKey).isNotBlank()) {
                    "Objet de panier obligatoire pour une preuve MEAL_BASKET"
                }
            }
        }
    }

    /**
     * Empreinte canonique d'un contrôle ACCO lié à un établissement exact.
     *
     * Le companyId est une identité locale stable et ne suffit pas à prouver que la recherche
     * officielle portait encore sur le même établissement après un changement de SIRET.
     */
    fun accoOfficialScopeId(siret: String?): String? {
        val normalized = siret.orEmpty().filter(Char::isDigit)
        return normalized.takeIf { it.length == 14 }?.let { "ACCO:SIRET:$it" }
    }

    fun knowledgeFor(
        proofs: List<Proof>,
        source: PayrollLegalArbitratorV2.Source,
        matter: Matter,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        subjectKey: String? = null
    ): PayrollLegalArbitratorV2.Knowledge {
        val confirmed = proofs.any { proof ->
            proof.source == source &&
                proof.matter == matter &&
                proof.exhaustive &&
                proof.scopeConfirmed &&
                proof.outcome == Outcome.NO_APPLICABLE_RULE &&
                !referenceDate.isBefore(proof.referenceFrom) &&
                !referenceDate.isAfter(proof.referenceTo) &&
                !proof.officialCoverageThrough.isBefore(referenceDate) &&
                scopeMatches(proof, source, companyId, idcc) &&
                subjectMatches(proof, matter, subjectKey)
        }
        return if (confirmed) PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
        else PayrollLegalArbitratorV2.Knowledge.UNKNOWN
    }

    fun knowledgeMapForOvertime(
        proofs: List<Proof>,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        knowledgeMapForMatter(
            proofs = proofs,
            matter = Matter.OVERTIME_RATE,
            companyId = companyId,
            idcc = idcc,
            referenceDate = referenceDate
        )

    fun knowledgeMapForProvidentContribution(
        proofs: List<Proof>,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        knowledgeMapForMatter(
            proofs = proofs,
            matter = Matter.PROVIDENT_CONTRIBUTION,
            companyId = companyId,
            idcc = idcc,
            referenceDate = referenceDate
        )

    fun knowledgeMapForMealBasketSubject(
        proofs: List<Proof>,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        subjectKey: String
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        knowledgeMapForMatter(
            proofs = proofs,
            matter = Matter.MEAL_BASKET,
            companyId = companyId,
            idcc = idcc,
            referenceDate = referenceDate,
            subjectKey = subjectKey
        )

    fun knowledgeMapForSeniorityPremium(
        proofs: List<Proof>,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> =
        knowledgeMapForMatter(
            proofs = proofs,
            matter = Matter.SENIORITY_PREMIUM,
            companyId = companyId,
            idcc = idcc,
            referenceDate = referenceDate
        )

    private fun knowledgeMapForMatter(
        proofs: List<Proof>,
        matter: Matter,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate,
        subjectKey: String? = null
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = buildMap {
        listOf(PayrollLegalArbitratorV2.Source.ACCO, PayrollLegalArbitratorV2.Source.KALI).forEach { source ->
            val knowledge = knowledgeFor(
                proofs = proofs,
                source = source,
                matter = matter,
                companyId = companyId,
                idcc = idcc,
                referenceDate = referenceDate,
                subjectKey = subjectKey
            )
            if (knowledge == PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE) {
                put(source, knowledge)
            }
        }
    }

    private fun scopeMatches(
        proof: Proof,
        source: PayrollLegalArbitratorV2.Source,
        companyId: String,
        idcc: String
    ): Boolean = when (source) {
        PayrollLegalArbitratorV2.Source.ACCO ->
            companyId.isNotBlank() && proof.companyId?.trim() == companyId.trim()

        PayrollLegalArbitratorV2.Source.KALI ->
            normalizeIdcc(proof.idcc) == normalizeIdcc(idcc) && normalizeIdcc(idcc).isNotBlank()

        else -> false
    }

    private fun subjectMatches(proof: Proof, matter: Matter, requested: String?): Boolean {
        if (matter != Matter.MEAL_BASKET) return true
        val wanted = normalizeSubject(requested)
        return wanted.isNotBlank() && normalizeSubject(proof.subjectKey) == wanted
    }

    private fun normalizeSubject(value: String?): String = value.orEmpty()
        .trim()
        .uppercase(Locale.ROOT)
        .replace(Regex("_[0-9]+$"), "")
        .takeIf { it.startsWith("MEAL_") }
        ?: ""

    private fun normalizeIdcc(value: String?): String {
        val raw = value.orEmpty().trim()
        return if (raw.isBlank()) "" else raw.padStart(4, '0')
    }
}
