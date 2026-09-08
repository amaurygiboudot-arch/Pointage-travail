package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Fournit les garanties conventionnelles de prévoyance vérifiées pour le profil salarié local.
 *
 * Une règle stockée seule ne suffit pas : la matière PROVIDENT_BENEFITS doit aussi avoir une
 * couverture officielle fiable pour la période. CONFIRMED_NO_RULE n'est accepté que lorsqu'il
 * vient d'un audit KALI explicite et exact du même profil.
 */
object VerifiedProvidentBenefitProviderV2 {
    data class Snapshot(
        val guarantees: List<ConventionProvidentBenefitV2.Guarantee>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(context: Context, companyId: String, referenceDate: LocalDate): Snapshot {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return unresolved("entreprise ou profil salarié introuvable")
        if (profile.idcc.isBlank()) return unresolved("IDCC manquant")
        if (profile.classification.isEmpty()) return unresolved("classification conventionnelle exacte manquante")
        if (profile.professionalStatus == null) return unresolved("statut cadre/non-cadre exact manquant")

        val category = VerifiedProtectionCategoryProviderV2.resolve(context, companyId, referenceDate)
        if (!category.reliable || !category.category.confirmed) {
            return unresolved("catégorie ANI KALI + APEC non confirmée")
        }
        val seniority = seniorityMonths(profile, referenceDate)
            ?: return unresolved("ancienneté conventionnelle non déterminable")
        val coverage = V2ConventionMatterCoverageStore.resolve(
            context = context,
            idcc = profile.idcc,
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_BENEFITS,
            date = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus
        )
        return resolve(
            profile = profile,
            referenceDate = referenceDate,
            protectionCategory = category.category,
            seniorityMonths = seniority,
            rules = V2ConventionProvidentBenefitStore.rules(context, profile.idcc),
            coverage = coverage
        )
    }

    internal fun resolve(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        protectionCategory: com.amaury.pointage.v2.engine.ProtectionCategoryV2.Result,
        seniorityMonths: Int,
        rules: List<ConventionProvidentBenefitV2.Rule>,
        coverage: ConventionMatterCoverageV2.Snapshot
    ): Snapshot {
        if (!coverage.reliable) return unresolved("audit officiel des garanties incomplet")
        val record = coverage.record ?: return unresolved("preuve de couverture des garanties absente")
        if (record.matter != ConventionMatterCoverageV2.Matter.PROVIDENT_BENEFITS ||
            ConventionMatterCoverageV2.Authority.KALI !in record.authorities
        ) {
            return unresolved("couverture KALI des garanties non prouvée")
        }

        if (coverage.state == ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE) {
            return Snapshot(
                guarantees = emptyList(),
                reliable = true,
                warnings = listOf("Garanties de prévoyance : absence explicitement confirmée par l'audit KALI pour ce profil et cette période.")
            )
        }
        if (coverage.state != ConventionMatterCoverageV2.State.CONFIRMED_RULES) {
            return unresolved("état de couverture des garanties non exploitable")
        }

        val resolution = ConventionProvidentBenefitV2.resolve(
            rules = rules,
            idcc = profile.idcc,
            referenceDate = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus,
            protectionCategory = protectionCategory,
            seniorityMonths = seniorityMonths
        )
        if (!resolution.reliable) {
            return Snapshot(emptyList(), false, resolution.warnings.distinct())
        }
        return Snapshot(
            guarantees = resolution.guarantees,
            reliable = true,
            warnings = resolution.warnings.distinct()
        )
    }

    private fun seniorityMonths(profile: ConventionLegalProfileV2, referenceDate: LocalDate): Int? {
        val start = profile.conventionSeniorityDate ?: profile.entryDate ?: return null
        if (start.isAfter(referenceDate)) return null
        return ChronoUnit.MONTHS.between(start, referenceDate).toInt().takeIf { it in 0..600 }
    }

    private fun unresolved(reason: String) = Snapshot(
        guarantees = emptyList(),
        reliable = false,
        warnings = listOf("Garanties de prévoyance vérifiées : $reason ; aucun droit n'est inventé.")
    )
}
