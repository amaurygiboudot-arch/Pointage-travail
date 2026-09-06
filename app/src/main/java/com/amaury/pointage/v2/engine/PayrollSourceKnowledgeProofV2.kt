package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Preuve explicite qu'une source juridique prioritaire a ete controlee pour une matiere,
 * un champ d'application et une date donnes.
 *
 * L'absence de donnees locales ne vaut jamais preuve d'absence officielle. Une source ne passe
 * a CONFIRMED_ABSENCE que si un controle officiel exhaustif a conclu qu'aucune regle applicable
 * n'existe, que le champ est confirme et que la couverture officielle atteint la date de paie.
 */
object PayrollSourceKnowledgeProofV2 {
    enum class Matter {
        OVERTIME_RATE
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
            require(!referenceTo.isBefore(referenceFrom)) { "Periode de preuve invalide" }
            require(checkedAtMs > 0L) { "Date de controle obligatoire" }
            require(officialScopeId.isNotBlank()) { "Empreinte du perimetre officiel obligatoire" }
        }
    }

    fun knowledgeFor(
        proofs: List<Proof>,
        source: PayrollLegalArbitratorV2.Source,
        matter: Matter,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
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
                scopeMatches(proof, source, companyId, idcc)
        }
        return if (confirmed) {
            PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
        } else {
            PayrollLegalArbitratorV2.Knowledge.UNKNOWN
        }
    }

    fun knowledgeMapForOvertime(
        proofs: List<Proof>,
        companyId: String,
        idcc: String,
        referenceDate: LocalDate
    ): Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = buildMap {
        listOf(PayrollLegalArbitratorV2.Source.ACCO, PayrollLegalArbitratorV2.Source.KALI).forEach { source ->
            val knowledge = knowledgeFor(
                proofs = proofs,
                source = source,
                matter = Matter.OVERTIME_RATE,
                companyId = companyId,
                idcc = idcc,
                referenceDate = referenceDate
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

    private fun normalizeIdcc(value: String?): String {
        val raw = value.orEmpty().trim()
        return if (raw.isBlank()) "" else raw.padStart(4, '0')
    }
}
