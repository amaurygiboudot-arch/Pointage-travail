package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.OfficialAccoProvidentContributionParserV2
import com.amaury.pointage.v2.V2CompanyProvidentContributionStore
import com.amaury.pointage.v2.V2ConventionProvidentContributionBridge
import java.time.LocalDate

/**
 * Arbitrage juridique des cotisations de prévoyance entre KALI et ACCO.
 *
 * Cette passerelle ne calcule aucun montant et ne modifie pas le moteur de salaire. Elle ne fait
 * qu'arbitrer des preuves déjà structurées en appliquant le bloc L2253-1. En particulier,
 * l'équivalence des garanties n'est jamais déduite des taux de cotisation : elle doit être fournie
 * explicitement par une future preuve séparée portant sur les garanties/prestations.
 */
object ProvidentContributionLegalArbitrationBridgeV2 {
    data class Snapshot(
        val resolution: PayrollLegalArbitratorV2.Resolution,
        val selectedBranchRule: ConventionProvidentContributionV2.Rule?,
        val selectedCompanyRule: OfficialAccoProvidentContributionParserV2.Rule?,
        val warnings: List<String>
    ) {
        val resolved: Boolean
            get() = resolution.state == PayrollLegalArbitratorV2.State.RESOLVED &&
                (selectedBranchRule != null || selectedCompanyRule != null)
    }

    fun load(
        context: Context,
        companyId: String,
        referenceDate: LocalDate,
        gross: Double,
        applicableMonthlyCeiling: Double?,
        companyGuaranteesEquivalent: Boolean? = null,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ): Snapshot {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return unavailable("Prévoyance ACCO/KALI : profil juridique local introuvable.")
        val branch = V2ConventionProvidentContributionBridge.load(
            context = context,
            companyId = companyId,
            referenceDate = referenceDate,
            gross = gross,
            applicableMonthlyCeiling = applicableMonthlyCeiling
        )
        val companyRules = V2CompanyProvidentContributionStore.rules(context, companyId)
        return resolve(
            profile = profile,
            referenceDate = referenceDate,
            branch = branch,
            companyRules = companyRules,
            companyGuaranteesEquivalent = companyGuaranteesEquivalent,
            sourceKnowledge = sourceKnowledge
        )
    }

    internal fun resolve(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        branch: V2ConventionProvidentContributionBridge.Snapshot,
        companyRules: List<OfficialAccoProvidentContributionParserV2.Rule>,
        companyGuaranteesEquivalent: Boolean? = null,
        sourceKnowledge: Map<PayrollLegalArbitratorV2.Source, PayrollLegalArbitratorV2.Knowledge> = emptyMap()
    ): Snapshot {
        val normalizedSiret = profile.siret.filter(Char::isDigit)
        if (normalizedSiret.length != 14) {
            return unavailable("Prévoyance ACCO/KALI : SIRET exact requis avant arbitrage d'entreprise.")
        }
        if (profile.idcc.isBlank() || profile.classification.isEmpty() || profile.professionalStatus == null) {
            return unavailable("Prévoyance ACCO/KALI : IDCC, classification et statut professionnel exacts requis.")
        }

        val candidates = mutableListOf<PayrollLegalArbitratorV2.Candidate>()
        val branchById = linkedMapOf<String, ConventionProvidentContributionV2.Rule>()
        val companyById = linkedMapOf<String, OfficialAccoProvidentContributionParserV2.Rule>()

        val branchCoverageTrusted = branch.coverage.reliable &&
            branch.coverage.record?.authorities?.contains(ConventionMatterCoverageV2.Authority.KALI) == true
        val branchRule = branch.result.selectedRule?.takeIf { rule ->
            branchCoverageTrusted &&
                branch.coverage.state == ConventionMatterCoverageV2.State.CONFIRMED_RULES &&
                branchRuleMatchesProfile(rule, profile, referenceDate)
        }
        branchRule?.let { rule ->
            val id = "KALI:${rule.ruleId}"
            candidates += PayrollLegalArbitratorV2.Candidate(
                id = id,
                source = PayrollLegalArbitratorV2.Source.KALI,
                effectiveFrom = rule.effectiveFrom,
                effectiveTo = rule.effectiveTo,
                verified = true,
                scopeConfirmed = true,
                valueFingerprint = branchFingerprint(rule)
            )
            branchById[id] = rule
        }

        val seniorityMonths = V2ConventionProvidentContributionBridge.seniorityMonths(profile, referenceDate)
        companyRules
            .filter { V2CompanyProvidentContributionStore.acceptsVerifiedRule(it, normalizedSiret) }
            .filter { companyRuleMatchesProfile(it, profile, referenceDate) }
            .forEach { rule ->
                val id = "ACCO:${rule.agreementId}:${rule.fingerprint.hashCode()}"
                val seniorityConfirmed = rule.minimumSeniorityMonths == 0 ||
                    (seniorityMonths != null && seniorityMonths >= rule.minimumSeniorityMonths)
                candidates += PayrollLegalArbitratorV2.Candidate(
                    id = id,
                    source = PayrollLegalArbitratorV2.Source.ACCO,
                    effectiveFrom = rule.effectiveFrom,
                    effectiveTo = rule.effectiveTo,
                    verified = true,
                    scopeConfirmed = seniorityConfirmed,
                    valueFingerprint = rule.fingerprint,
                    companyGuaranteesEquivalent = companyGuaranteesEquivalent
                )
                companyById[id] = rule
            }

        val effectiveKnowledge = sourceKnowledge
            .filterKeys { it != PayrollLegalArbitratorV2.Source.KALI }
            .toMutableMap()
        if (
            branchCoverageTrusted &&
            branch.coverage.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE
        ) {
            effectiveKnowledge[PayrollLegalArbitratorV2.Source.KALI] =
                PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
        }

        val resolution = PayrollLegalArbitratorV2.resolve(
            candidates = candidates,
            referenceDate = referenceDate,
            policy = PayrollLegalArbitratorV2.Policy.BRANCH_BLOCK_L2253_1,
            sourceKnowledge = effectiveKnowledge
        )
        val selectedId = resolution.selected?.id
        return Snapshot(
            resolution = resolution,
            selectedBranchRule = selectedId?.let(branchById::get),
            selectedCompanyRule = selectedId?.let(companyById::get),
            warnings = warnings(resolution, branchCoverageTrusted, companyRules.isNotEmpty())
        )
    }

    private fun branchRuleMatchesProfile(
        rule: ConventionProvidentContributionV2.Rule,
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate
    ): Boolean =
        rule.structurallyValid() &&
            ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc) == ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc) &&
            rule.activeOn(referenceDate) &&
            rule.classification == profile.classification &&
            rule.professionalStatus?.trim()?.uppercase() == profile.professionalStatus?.trim()?.uppercase()

    private fun companyRuleMatchesProfile(
        rule: OfficialAccoProvidentContributionParserV2.Rule,
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate
    ): Boolean =
        rule.siret.filter(Char::isDigit) == profile.siret.filter(Char::isDigit) &&
            rule.classification == profile.classification &&
            rule.professionalStatus.trim().uppercase() == profile.professionalStatus?.trim()?.uppercase() &&
            !referenceDate.isBefore(rule.effectiveFrom) &&
            (rule.effectiveTo == null || !referenceDate.isAfter(rule.effectiveTo))

    private fun branchFingerprint(rule: ConventionProvidentContributionV2.Rule): String = buildString {
        append("KALI_PROVIDENT|")
        append(ConventionMinimumSalaryV2.normalizeIdcc(rule.idcc)).append('|')
        append(rule.effectiveFrom).append('|').append(rule.effectiveTo).append('|')
        append(rule.classification.label()).append('|').append(rule.professionalStatus).append('|')
        rule.aniCategories.sortedBy { it.name }.forEach { append(it.name).append(',') }
        append('|')
        rule.tiers.sortedBy { it.minimumSeniorityMonths }.forEach { tier ->
            append(tier.minimumSeniorityMonths).append(':')
            tier.bands.sortedBy { it.lowerCeilingMultiple }.forEach { band ->
                append(band.lowerCeilingMultiple).append('-')
                append(band.upperCeilingMultiple).append('@')
                append(band.employeeRate).append('/').append(band.employerRate).append(';')
            }
            append('|')
        }
    }

    private fun warnings(
        resolution: PayrollLegalArbitratorV2.Resolution,
        branchCoverageTrusted: Boolean,
        hasStoredCompanyRules: Boolean
    ): List<String> = buildList {
        when (resolution.state) {
            PayrollLegalArbitratorV2.State.RESOLVED -> when (resolution.selected?.source) {
                PayrollLegalArbitratorV2.Source.KALI ->
                    add("Prévoyance : règle KALI retenue après arbitrage L2253-1.")
                PayrollLegalArbitratorV2.Source.ACCO ->
                    add("Prévoyance : règle ACCO retenue uniquement après confirmation explicite de l'équivalence des garanties.")
                else -> add("Prévoyance : arbitrage L2253-1 résolu.")
            }
            PayrollLegalArbitratorV2.State.CONFLICT ->
                add("Prévoyance : plusieurs règles applicables se contredisent ; aucun choix automatique n'est autorisé.")
            PayrollLegalArbitratorV2.State.REVIEW_REQUIRED ->
                add("Prévoyance : contrôle L2253-1 requis avant toute application automatique d'une règle d'entreprise ou de branche.")
            PayrollLegalArbitratorV2.State.NO_APPLICABLE_RULE ->
                add("Prévoyance : aucune règle collective applicable n'est retenue par l'arbitrage courant.")
        }
        if (!branchCoverageTrusted) {
            add("Prévoyance : couverture KALI fiable non démontrée pour ce profil et cette période.")
        }
        if (hasStoredCompanyRules && resolution.selected?.source != PayrollLegalArbitratorV2.Source.ACCO) {
            add("Prévoyance : une preuve ACCO locale existe mais n'est pas automatiquement prioritaire sur la branche.")
        }
    }.distinct()

    private fun unavailable(reason: String): Snapshot = Snapshot(
        resolution = PayrollLegalArbitratorV2.Resolution(
            state = PayrollLegalArbitratorV2.State.REVIEW_REQUIRED,
            selected = null,
            considered = emptyList(),
            ignoredPublicationEvidence = emptyList(),
            explanation = reason
        ),
        selectedBranchRule = null,
        selectedCompanyRule = null,
        warnings = listOf(reason)
    )
}
