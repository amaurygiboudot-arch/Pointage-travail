package com.amaury.pointage.v2.engine

import android.content.Context
import com.amaury.pointage.v2.ConventionLegalProfileV2
import com.amaury.pointage.v2.V2CompanyProvidentBenefitStore
import com.amaury.pointage.v2.V2ConventionProvidentContributionBridge
import com.amaury.pointage.v2.VerifiedProvidentBenefitProviderV2
import java.time.LocalDate
import java.util.Locale

/**
 * Preuve conservative de l'équivalence L2253-1 pour la matière prévoyance.
 *
 * Le Code du travail impose une appréciation par ensemble de garanties de la même matière. Cette
 * première preuve automatique ne prétend donc jamais qu'une formule différente est "plus
 * favorable" : elle confirme uniquement l'équivalence lorsque chaque garantie KALI structurée est
 * retrouvée à l'identique dans le paquet ACCO complet applicable. Des garanties ACCO supplémentaires
 * sont admises. Toute différence de formule, carence, durée, catégorie ou traitement SS => inconnu.
 */
object CompanyProvidentGuaranteeEquivalenceV2 {
    data class Result(
        val equivalent: Boolean?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        context: Context,
        companyId: String,
        referenceDate: LocalDate,
        contributionAgreementIds: Set<String>
    ): Result {
        if (contributionAgreementIds.isEmpty()) return unresolved("aucun ACCOTEXT de cotisation candidat")
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return unresolved("profil juridique local introuvable")
        val seniority = V2ConventionProvidentContributionBridge.seniorityMonths(profile, referenceDate)
            ?: return unresolved("ancienneté conventionnelle non déterminable")
        val branch = VerifiedProvidentBenefitProviderV2.resolve(context, companyId, referenceDate)
        return resolve(
            profile = profile,
            referenceDate = referenceDate,
            seniorityMonths = seniority,
            branch = branch,
            companyRules = V2CompanyProvidentBenefitStore.rules(context, companyId),
            contributionAgreementIds = contributionAgreementIds
        )
    }

    internal fun resolve(
        profile: ConventionLegalProfileV2,
        referenceDate: LocalDate,
        seniorityMonths: Int,
        branch: VerifiedProvidentBenefitProviderV2.Snapshot,
        companyRules: List<CompanyProvidentBenefitV2.Rule>,
        contributionAgreementIds: Set<String>
    ): Result {
        if (!branch.reliable) return unresolved("paquet KALI de garanties non fiable", branch.warnings)
        if (branch.guarantees.isEmpty()) {
            return unresolved("aucune garantie KALI structurée à comparer ; l'équivalence n'est pas déduite d'une absence")
        }
        val normalizedIds = contributionAgreementIds
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.matches(Regex("^ACCOTEXT\\d+$")) }
            .toSet()
        if (normalizedIds.isEmpty()) return unresolved("identifiant ACCOTEXT de cotisation invalide")

        val expectedSiret = profile.siret.filter(Char::isDigit)
        if (expectedSiret.length != 14) return unresolved("SIRET exact du profil manquant")
        if (profile.classification.isEmpty() || profile.professionalStatus == null) {
            return unresolved("classification ou statut professionnel exact manquant")
        }

        val applicable = companyRules.filter { rule ->
            rule.structurallyValid() &&
                rule.agreementId.trim().uppercase(Locale.ROOT) in normalizedIds &&
                rule.siret.filter(Char::isDigit) == expectedSiret &&
                rule.activeOn(referenceDate) &&
                rule.classification == profile.classification &&
                rule.professionalStatus.trim().uppercase(Locale.ROOT) == profile.professionalStatus.trim().uppercase(Locale.ROOT) &&
                seniorityMonths >= rule.minimumSeniorityMonths
        }

        val branchFingerprints = branch.guarantees.map(CompanyProvidentBenefitV2::fingerprint).toSet()
        if (branchFingerprints.isEmpty()) return unresolved("empreinte du paquet KALI vide")

        normalizedIds.forEach { agreementId ->
            val agreementRules = applicable.filter { it.agreementId.equals(agreementId, ignoreCase = true) }
            if (agreementRules.isEmpty()) {
                return unresolved("$agreementId : aucune garantie ACCO applicable et structurée ne porte la cotisation candidate")
            }
            if (agreementRules.any { !it.packageComplete }) {
                return unresolved("$agreementId : paquet ACCO incomplet ; équivalence L2253-1 non démontrée")
            }

            val byFamily = agreementRules.groupBy { it.guarantee.family to it.guarantee.invalidityCategory }
            val selected = mutableListOf<CompanyProvidentBenefitV2.Guarantee>()
            byFamily.forEach { (key, values) ->
                val latestDate = values.maxOf { it.effectiveFrom }
                val latest = values.filter { it.effectiveFrom == latestDate }
                val maxSeniority = latest.maxOf { it.minimumSeniorityMonths }
                val best = latest.filter { it.minimumSeniorityMonths == maxSeniority }
                val fingerprints = best.map { CompanyProvidentBenefitV2.fingerprint(it.guarantee) }.distinct()
                if (fingerprints.size != 1) {
                    return unresolved("$agreementId : garanties ${key.first}${key.second?.let { " catégorie $it" }.orEmpty()} contradictoires")
                }
                selected += best.first().guarantee
            }

            val companyFingerprints = selected.map(CompanyProvidentBenefitV2::fingerprint).toSet()
            if (!companyFingerprints.containsAll(branchFingerprints)) {
                return unresolved(
                    "$agreementId : le paquet ACCO ne reproduit pas exactement toutes les garanties KALI structurées ; une appréciation plus favorable n'est pas inventée"
                )
            }
        }

        return Result(
            equivalent = true,
            reliable = true,
            warnings = listOf(
                "Prévoyance L2253-1 : équivalence confirmée uniquement parce que chaque ACCOTEXT de cotisation candidat porte un paquet complet reproduisant toutes les garanties KALI structurées de la matière."
            )
        )
    }

    private fun unresolved(reason: String, extra: List<String> = emptyList()) = Result(
        equivalent = null,
        reliable = false,
        warnings = (listOf("Prévoyance L2253-1 : $reason ; équivalence à confirmer.") + extra).distinct()
    )
}
