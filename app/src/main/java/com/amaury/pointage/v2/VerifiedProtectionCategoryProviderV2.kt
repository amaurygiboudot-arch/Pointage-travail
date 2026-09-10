package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import java.time.LocalDate

/**
 * Fournit au moteur de paie la catégorie ANI générique vérifiée localement.
 *
 * Aucun fallback vers une convention codée en dur n'est effectué ici. En l'absence de preuve
 * KALI/APEC exacte, le résultat reste TO_CONFIRM et les calculs dépendants doivent se bloquer.
 */
object VerifiedProtectionCategoryProviderV2 {
    data class Snapshot(
        val category: ProtectionCategoryV2.Result,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(context: Context, companyId: String, referenceDate: LocalDate): Snapshot {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return unresolved("entreprise ou profil salarié introuvable")
        if (profile.idcc.isBlank()) return unresolved("IDCC manquant")
        if (profile.classification.isEmpty()) return unresolved("classification conventionnelle exacte manquante")
        if (profile.professionalStatus == null) return unresolved("statut cadre/non-cadre exact manquant")

        val coverage = V2ConventionMatterCoverageStore.resolve(
            context = context,
            idcc = profile.idcc,
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CATEGORY,
            date = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus
        )
        val stored = V2ConventionProtectionCategoryStore.readVerified(context)
        if (!stored.reliable) {
            return Snapshot(
                category = ProtectionCategoryV2.Result(
                    aniCategory = ProtectionCategoryV2.AniCategory.TO_CONFIRM,
                    confirmed = false,
                    warnings = stored.warnings
                ),
                reliable = false,
                warnings = (stored.warnings +
                    "Catégorie ANI vérifiée : cache KALI/APEC local incohérent ; aucun classement n'est déduit tant que le stockage n'est pas réparé.")
                    .distinct()
            )
        }
        val normalizedIdcc = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)
        val rules = stored.rules.filter {
            ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalizedIdcc
        }
        return resolve(
            profile = profile,
            referenceDate = referenceDate,
            rules = rules,
            coverage = coverage
        )
    }

    internal fun resolve(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        rules: List<ConventionProtectionCategoryV2.Rule>,
        coverage: ConventionMatterCoverageV2.Snapshot? = null
    ): Snapshot {
        if (profile.idcc.isBlank()) return unresolved("IDCC manquant")
        if (profile.classification.isEmpty()) return unresolved("classification conventionnelle exacte manquante")
        if (profile.professionalStatus == null) return unresolved("statut cadre/non-cadre exact manquant")

        val resolution = ConventionProtectionCategoryV2.resolve(
            idcc = profile.idcc,
            referenceDate = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus,
            rules = rules,
            coverage = coverage
        )
        return Snapshot(
            category = resolution.category,
            reliable = resolution.reliable,
            warnings = resolution.warnings.distinct()
        )
    }

    private fun unresolved(reason: String): Snapshot {
        val warning = "Catégorie ANI vérifiée : $reason ; aucun classement n'est inventé."
        return Snapshot(
            category = ProtectionCategoryV2.Result(
                aniCategory = ProtectionCategoryV2.AniCategory.TO_CONFIRM,
                confirmed = false,
                warnings = listOf(warning)
            ),
            reliable = false,
            warnings = listOf(warning)
        )
    }
}
