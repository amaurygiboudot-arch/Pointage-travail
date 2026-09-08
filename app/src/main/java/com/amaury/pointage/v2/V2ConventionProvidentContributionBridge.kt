package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionProvidentContributionV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Bridge local entre les preuves KALI vérifiées et le calcul générique des cotisations de prévoyance.
 *
 * Aucune règle historique codée en dur n'est utilisée ici. Le calcul n'est autorisé que si :
 * - le profil juridique local est complet ;
 * - la catégorie ANI est confirmée ;
 * - la matière PROVIDENT_CONTRIBUTION est CONFIRMED_RULES ;
 * - le record de couverture prouve explicitement l'autorité KALI ;
 * - une règle locale vérifiée correspond exactement au profil et à la période.
 */
object V2ConventionProvidentContributionBridge {
    data class Snapshot(
        val result: ConventionProvidentContributionV2.Result,
        val coverage: ConventionMatterCoverageV2.Snapshot
    )

    fun load(
        context: Context,
        companyId: String,
        referenceDate: LocalDate,
        gross: Double,
        applicableMonthlyCeiling: Double?
    ): Snapshot {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return Snapshot(
                result = blocked("Prévoyance conventionnelle : entreprise ou profil salarié introuvable."),
                coverage = missingCoverage()
            )

        val coverage = V2ConventionMatterCoverageStore.resolve(
            context = context,
            idcc = profile.idcc,
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
            date = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus
        )
        val category = VerifiedProtectionCategoryProviderV2.resolve(context, companyId, referenceDate).category
        val seniorityMonths = seniorityMonths(profile, referenceDate)

        return resolve(
            profile = profile,
            referenceDate = referenceDate,
            protectionCategory = category,
            rules = V2ConventionProvidentContributionStore.rules(context, profile.idcc),
            coverage = coverage,
            gross = gross,
            applicableMonthlyCeiling = applicableMonthlyCeiling,
            seniorityMonths = seniorityMonths
        )
    }

    internal fun resolve(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        protectionCategory: ProtectionCategoryV2.Result,
        rules: List<ConventionProvidentContributionV2.Rule>,
        coverage: ConventionMatterCoverageV2.Snapshot,
        gross: Double,
        applicableMonthlyCeiling: Double?,
        seniorityMonths: Int?
    ): Snapshot {
        if (profile.idcc.isBlank()) {
            return Snapshot(blocked("Prévoyance conventionnelle : IDCC manquant."), coverage)
        }
        if (profile.classification.isEmpty()) {
            return Snapshot(blocked("Prévoyance conventionnelle IDCC ${profile.idcc} : classification conventionnelle exacte manquante."), coverage)
        }
        if (profile.professionalStatus == null) {
            return Snapshot(blocked("Prévoyance conventionnelle IDCC ${profile.idcc} : statut cadre/non-cadre exact manquant."), coverage)
        }
        if (!protectionCategory.confirmed) {
            return Snapshot(
                blocked("Prévoyance conventionnelle IDCC ${profile.idcc} : catégorie ANI vérifiée requise avant calcul."),
                coverage
            )
        }
        if (coverage.state != ConventionMatterCoverageV2.State.CONFIRMED_RULES || !coverage.reliable) {
            return Snapshot(
                blocked(
                    "Prévoyance conventionnelle IDCC ${profile.idcc} : audit officiel des cotisations incomplet ; aucun barème n'est appliqué.",
                    coverage.warnings
                ),
                coverage
            )
        }
        if (coverage.record?.authorities?.contains(ConventionMatterCoverageV2.Authority.KALI) != true) {
            return Snapshot(
                blocked("Prévoyance conventionnelle IDCC ${profile.idcc} : autorité KALI non prouvée par le record de couverture."),
                coverage
            )
        }

        val calculated = ConventionProvidentContributionV2.calculate(
            rules = rules,
            idcc = profile.idcc,
            referenceDate = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus,
            protectionCategory = protectionCategory,
            seniorityMonths = seniorityMonths,
            gross = gross,
            applicableMonthlyCeiling = applicableMonthlyCeiling
        )
        return Snapshot(
            result = calculated.copy(
                reliable = calculated.reliable && coverage.reliable,
                warnings = (calculated.warnings + coverage.warnings).distinct()
            ),
            coverage = coverage
        )
    }

    internal fun seniorityMonths(profile: ConventionLegalProfileV2, referenceDate: LocalDate): Int? {
        val start = profile.conventionSeniorityDate ?: profile.entryDate ?: return null
        if (start.isAfter(referenceDate)) return null
        return ChronoUnit.MONTHS.between(start, referenceDate).toInt().takeIf { it in 0..600 }
    }

    private fun blocked(reason: String, extraWarnings: List<String> = emptyList()) =
        ConventionProvidentContributionV2.Result(
            applicable = false,
            eligibilityConfirmed = false,
            reliable = false,
            selectedRule = null,
            selectedTier = null,
            lines = emptyList(),
            employeeAmount = null,
            employerAmount = null,
            warnings = (listOf(reason) + extraWarnings).distinct()
        )

    private fun missingCoverage() = ConventionMatterCoverageV2.Snapshot(
        state = ConventionMatterCoverageV2.State.INCOMPLETE,
        record = null,
        reliable = false,
        warnings = listOf("Prévoyance conventionnelle : couverture juridique indisponible.")
    )
}
