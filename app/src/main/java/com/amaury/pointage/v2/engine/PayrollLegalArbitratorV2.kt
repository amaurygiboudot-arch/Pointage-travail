package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * Arbitre uniquement des règles déjà vérifiées, datées et dont le champ d'application est confirmé.
 *
 * Important : BOCC et JORF sont des pistes de publication/veille. Ils ne deviennent jamais, à eux
 * seuls, une règle chiffrée de calcul. Les règles de calcul consolidées proviennent de LEGI, KALI,
 * ACCO ou, plus tard, d'une stipulation contractuelle explicitement structurée.
 *
 * Les politiques ci-dessous ne prétendent pas créer une hiérarchie universelle : l'articulation
 * entreprise/branche dépend de la matière. Le moteur exige donc qu'une politique juridique soit
 * choisie explicitement pour chaque famille de règle.
 */
object PayrollLegalArbitratorV2 {
    enum class Source {
        LEGI,
        KALI,
        ACCO,
        BOCC,
        JORF,
        CONTRACT
    }

    enum class SourceRole {
        NATIONAL_CONSOLIDATED_RULE,
        BRANCH_CONSOLIDATED_RULE,
        COMPANY_AGREEMENT_RULE,
        PUBLICATION_EVIDENCE,
        CONTRACTUAL_RULE
    }

    enum class Policy {
        /**
         * Code du travail L3121-33 : accord d'entreprise/établissement ou, à défaut, accord de branche.
         * L3121-36 : barème légal supplétif uniquement à défaut d'accord.
         */
        OVERTIME_RATE_L3121_33_36,

        /** Matières du bloc 1 de l'article L2253-1. */
        BRANCH_BLOCK_L2253_1,

        /** Matières du bloc 2 de l'article L2253-2 lorsqu'un verrou de branche existe. */
        BRANCH_LOCK_L2253_2,

        /** Matières hors blocs 1 et 2 : principe de L2253-3. */
        ENTERPRISE_PREVAILS_L2253_3,

        /** Pas d'automatisme tant qu'une politique plus précise n'est pas codée. */
        MANUAL_REVIEW
    }

    enum class State {
        RESOLVED,
        NO_APPLICABLE_RULE,
        CONFLICT,
        REVIEW_REQUIRED
    }

    data class Candidate(
        val id: String,
        val source: Source,
        val effectiveFrom: LocalDate?,
        val effectiveTo: LocalDate? = null,
        val verified: Boolean,
        val scopeConfirmed: Boolean,
        /** Empreinte stable de la règle structurée, utilisée uniquement pour détecter les conflits. */
        val valueFingerprint: String,
        /** L2253-1/2 : équivalence des garanties explicitement confirmée, jamais déduite automatiquement. */
        val companyGuaranteesEquivalent: Boolean? = null,
        /** L2253-2 : la convention de branche verrouille-t-elle explicitement cette matière ? */
        val branchLockConfirmed: Boolean? = null
    ) {
        init {
            require(id.isNotBlank()) { "Identifiant de règle obligatoire" }
            require(valueFingerprint.isNotBlank()) { "Empreinte de règle obligatoire" }
            require(effectiveTo == null || effectiveFrom == null || !effectiveTo.isBefore(effectiveFrom)) {
                "Période juridique invalide"
            }
        }
    }

    data class Resolution(
        val state: State,
        val selected: Candidate?,
        val considered: List<Candidate>,
        val ignoredPublicationEvidence: List<Candidate>,
        val explanation: String
    )

    fun role(source: Source): SourceRole = when (source) {
        Source.LEGI -> SourceRole.NATIONAL_CONSOLIDATED_RULE
        Source.KALI -> SourceRole.BRANCH_CONSOLIDATED_RULE
        Source.ACCO -> SourceRole.COMPANY_AGREEMENT_RULE
        Source.BOCC, Source.JORF -> SourceRole.PUBLICATION_EVIDENCE
        Source.CONTRACT -> SourceRole.CONTRACTUAL_RULE
    }

    fun resolve(
        candidates: List<Candidate>,
        referenceDate: LocalDate,
        policy: Policy
    ): Resolution {
        val evidence = candidates.filter { role(it.source) == SourceRole.PUBLICATION_EVIDENCE }
        val applicable = candidates
            .filter { role(it.source) != SourceRole.PUBLICATION_EVIDENCE }
            .filter { it.verified && it.scopeConfirmed && applies(it, referenceDate) }
            .sortedWith(compareBy<Candidate> { it.source.name }.thenBy { it.id })

        if (policy == Policy.MANUAL_REVIEW) {
            return Resolution(
                state = if (applicable.isEmpty()) State.NO_APPLICABLE_RULE else State.REVIEW_REQUIRED,
                selected = null,
                considered = applicable,
                ignoredPublicationEvidence = evidence,
                explanation = if (applicable.isEmpty()) {
                    "Aucune règle consolidée, vérifiée, datée et applicable n'est disponible."
                } else {
                    "Cette matière n'a pas encore de politique d'arbitrage automatique sûre."
                }
            )
        }

        if (applicable.isEmpty()) {
            return Resolution(
                State.NO_APPLICABLE_RULE,
                null,
                emptyList(),
                evidence,
                "Aucune règle consolidée, vérifiée, datée et applicable n'est disponible."
            )
        }

        return when (policy) {
            Policy.OVERTIME_RATE_L3121_33_36 -> resolveByPriority(
                applicable,
                evidence,
                listOf(Source.ACCO, Source.KALI, Source.LEGI),
                "Heures supplémentaires : accord d'entreprise/établissement, à défaut branche, à défaut règle légale supplétive."
            )

            Policy.ENTERPRISE_PREVAILS_L2253_3 -> resolveByPriority(
                applicable,
                evidence,
                listOf(Source.ACCO, Source.KALI, Source.LEGI),
                "Matière hors blocs L2253-1/L2253-2 : la règle d'entreprise est examinée avant la branche."
            )

            Policy.BRANCH_BLOCK_L2253_1 -> resolveBranchProtected(applicable, evidence)
            Policy.BRANCH_LOCK_L2253_2 -> resolveBranchLockable(applicable, evidence)
            Policy.MANUAL_REVIEW -> error("déjà traité")
        }
    }

    private fun resolveBranchProtected(
        applicable: List<Candidate>,
        evidence: List<Candidate>
    ): Resolution {
        val branch = uniqueFor(Source.KALI, applicable)
        if (branch.state != null) return conflict(branch.state, applicable, evidence, branch.message)
        val company = uniqueFor(Source.ACCO, applicable)
        if (company.state != null) return conflict(company.state, applicable, evidence, company.message)

        val branchRule = branch.candidate
        val companyRule = company.candidate
        if (branchRule != null && companyRule != null) {
            return when (companyRule.companyGuaranteesEquivalent) {
                true -> resolved(companyRule, applicable, evidence,
                    "Bloc L2253-1 : l'accord d'entreprise est retenu car l'équivalence des garanties avec la branche a été explicitement confirmée.")
                false -> resolved(branchRule, applicable, evidence,
                    "Bloc L2253-1 : la garantie de branche prévaut ; l'équivalence de l'accord d'entreprise n'est pas satisfaite.")
                null -> review(applicable, evidence,
                    "Bloc L2253-1 : l'équivalence des garanties doit être confirmée avant d'arbitrer entre branche et entreprise.")
            }
        }
        if (branchRule != null) return resolved(branchRule, applicable, evidence,
            "Bloc L2253-1 : règle de branche applicable.")
        if (companyRule != null) return review(applicable, evidence,
            "Bloc L2253-1 : une règle d'entreprise existe mais la garantie de branche applicable n'est pas disponible pour comparaison.")

        return fallbackNational(applicable, evidence,
            "Bloc L2253-1 : aucune règle de branche structurée applicable n'est disponible.")
    }

    private fun resolveBranchLockable(
        applicable: List<Candidate>,
        evidence: List<Candidate>
    ): Resolution {
        val branch = uniqueFor(Source.KALI, applicable)
        if (branch.state != null) return conflict(branch.state, applicable, evidence, branch.message)
        val company = uniqueFor(Source.ACCO, applicable)
        if (company.state != null) return conflict(company.state, applicable, evidence, company.message)

        val branchRule = branch.candidate
        val companyRule = company.candidate
        if (branchRule == null) {
            return if (companyRule != null) review(applicable, evidence,
                "Bloc L2253-2 : impossible de savoir si la branche a verrouillé la matière tant que sa règle n'est pas structurée.")
            else fallbackNational(applicable, evidence, "Bloc L2253-2 : aucune règle collective applicable disponible.")
        }

        return when (branchRule.branchLockConfirmed) {
            true -> {
                if (companyRule == null) resolved(branchRule, applicable, evidence,
                    "Bloc L2253-2 verrouillé par la branche : règle de branche applicable.")
                else when (companyRule.companyGuaranteesEquivalent) {
                    true -> resolved(companyRule, applicable, evidence,
                        "Bloc L2253-2 verrouillé : l'accord d'entreprise est retenu après confirmation explicite de garanties au moins équivalentes.")
                    false -> resolved(branchRule, applicable, evidence,
                        "Bloc L2253-2 verrouillé : la règle de branche prévaut.")
                    null -> review(applicable, evidence,
                        "Bloc L2253-2 verrouillé : l'équivalence des garanties doit être contrôlée.")
                }
            }
            false -> {
                if (companyRule != null) resolved(companyRule, applicable, evidence,
                    "Bloc L2253-2 non verrouillé : la règle d'entreprise applicable est retenue.")
                else resolved(branchRule, applicable, evidence,
                    "Bloc L2253-2 non verrouillé : aucune règle d'entreprise applicable, règle de branche retenue.")
            }
            null -> review(applicable, evidence,
                "Bloc L2253-2 : le caractère verrouillé ou non de la matière par la branche doit être confirmé.")
        }
    }

    private fun resolveByPriority(
        applicable: List<Candidate>,
        evidence: List<Candidate>,
        priority: List<Source>,
        explanation: String
    ): Resolution {
        priority.forEach { source ->
            val choice = uniqueFor(source, applicable)
            if (choice.state != null) return conflict(choice.state, applicable, evidence, choice.message)
            choice.candidate?.let { return resolved(it, applicable, evidence, explanation) }
        }
        return review(applicable, evidence,
            "Des règles applicables existent, mais aucune ne correspond aux sources autorisées par cette politique.")
    }

    private data class UniqueChoice(
        val candidate: Candidate?,
        val state: State? = null,
        val message: String = ""
    )

    private fun uniqueFor(source: Source, applicable: List<Candidate>): UniqueChoice {
        val items = applicable.filter { it.source == source }
        if (items.isEmpty()) return UniqueChoice(null)
        val fingerprints = items.map { it.valueFingerprint }.distinct()
        if (fingerprints.size > 1) {
            return UniqueChoice(
                null,
                State.CONFLICT,
                "Plusieurs règles $source applicables portent des valeurs incompatibles ; aucun choix automatique n'est autorisé."
            )
        }
        return UniqueChoice(items.minByOrNull { it.id })
    }

    private fun applies(candidate: Candidate, referenceDate: LocalDate): Boolean {
        val from = candidate.effectiveFrom ?: return false
        return !referenceDate.isBefore(from) &&
            (candidate.effectiveTo == null || !referenceDate.isAfter(candidate.effectiveTo))
    }

    private fun fallbackNational(
        applicable: List<Candidate>,
        evidence: List<Candidate>,
        prefix: String
    ): Resolution {
        val legal = uniqueFor(Source.LEGI, applicable)
        if (legal.state != null) return conflict(legal.state, applicable, evidence, legal.message)
        return legal.candidate?.let {
            resolved(it, applicable, evidence, "$prefix Règle nationale consolidée retenue comme repli.")
        } ?: review(applicable, evidence, "$prefix Aucun repli national structuré n'est disponible.")
    }

    private fun resolved(
        selected: Candidate,
        applicable: List<Candidate>,
        evidence: List<Candidate>,
        explanation: String
    ) = Resolution(State.RESOLVED, selected, applicable, evidence, explanation)

    private fun review(
        applicable: List<Candidate>,
        evidence: List<Candidate>,
        explanation: String
    ) = Resolution(State.REVIEW_REQUIRED, null, applicable, evidence, explanation)

    private fun conflict(
        state: State,
        applicable: List<Candidate>,
        evidence: List<Candidate>,
        explanation: String
    ) = Resolution(state, null, applicable, evidence, explanation)
}
