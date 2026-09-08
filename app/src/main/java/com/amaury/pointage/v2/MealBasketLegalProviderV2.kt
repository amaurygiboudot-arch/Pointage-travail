package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.MealBasketLegalArbitrationBridgeV2
import java.time.LocalDate

/**
 * Charge uniquement les preuves locales nécessaires à l'arbitrage des paniers repas.
 *
 * Une règle KALI en cache n'est jamais utilisée sans couverture MEAL_BASKET fiable pour le profil
 * et la date. Une règle ACCO reste liée au SIRET exact et doit provenir d'un paquet dont le dernier
 * état d'audit persistant est complet. Toute occurrence ACCO non résolue bloque la paie.
 */
object MealBasketLegalProviderV2 {
    data class Snapshot(
        val resolution: MealBasketLegalArbitrationBridgeV2.Result,
        val branchCoverage: ConventionMatterCoverageV2.Snapshot?,
        val branchRulesLoaded: Int,
        val companyRulesLoaded: Int,
        val warnings: List<String>
    ) {
        val reliable: Boolean get() = resolution.reliable
    }

    fun load(
        context: Context,
        companyId: String,
        expectedIdcc: String,
        referenceDate: LocalDate,
        territoryCode: String? = null
    ): Snapshot {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return blocked("profil juridique local introuvable")
        val normalizedExpected = ConventionMinimumSalaryV2.normalizeIdcc(expectedIdcc)
        val normalizedProfile = ConventionMinimumSalaryV2.normalizeIdcc(profile.idcc)
        if (normalizedExpected.isBlank() || normalizedProfile != normalizedExpected) {
            return blocked("IDCC du profil différent de l'IDCC demandé")
        }
        val normalizedSiret = profile.siret.filter(Char::isDigit)
        val status = profile.professionalStatus?.trim()?.uppercase()
        if (normalizedSiret.length != 14 || profile.classification.isEmpty() || status == null) {
            return blocked("SIRET, classification ou statut professionnel incomplet")
        }

        val unresolvedAcco = V2CompanyMealBasketAuditStateStore.unresolvedFor(
            context = context,
            companyId = companyId,
            expectedSiret = normalizedSiret,
            classification = profile.classification,
            professionalStatus = status
        )
        if (unresolvedAcco.isNotEmpty()) {
            return blocked(
                "audit ACCO repas incomplet pour ${unresolvedAcco.joinToString { it.agreementId }} ; une règle ancienne ne peut pas masquer l'incertitude"
            )
        }

        val storedCompany = V2CompanyMealBasketStore.rules(
            context = context,
            companyId = companyId,
            expectedSiret = normalizedSiret
        )
        val completeAgreementIds = V2CompanyMealBasketAuditStateStore.completeAgreementIdsFor(
            context = context,
            companyId = companyId,
            expectedSiret = normalizedSiret,
            classification = profile.classification,
            professionalStatus = status
        )
        val unmarkedCompanyRules = storedCompany.filter { it.agreementId !in completeAgreementIds }
        if (unmarkedCompanyRules.isNotEmpty()) {
            return blocked(
                "règle(s) ACCO repas issue(s) d'un ancien cache sans marqueur d'audit complet ; nouvel audit requis avant calcul"
            )
        }
        val companyRules = storedCompany.filter { it.agreementId in completeAgreementIds }

        val coverage = V2ConventionMatterCoverageStore.resolve(
            context = context,
            idcc = normalizedProfile,
            matter = ConventionMatterCoverageV2.Matter.MEAL_BASKET,
            date = referenceDate,
            classification = profile.classification,
            professionalStatus = profile.professionalStatus
        )
        val storedBranch = V2ConventionMealBasketStore.rules(context, normalizedProfile)
        val branchTrusted = coverage.reliable &&
            coverage.state == ConventionMatterCoverageV2.State.CONFIRMED_RULES &&
            coverage.record?.authorities?.contains(ConventionMatterCoverageV2.Authority.KALI) == true
        val branchRules = if (branchTrusted) storedBranch else emptyList()

        val subjects = (branchRules.map { MealBasketLegalArbitrationBridgeV2.subject(it.benefitId) } +
            companyRules.map { MealBasketLegalArbitrationBridgeV2.subject(it.benefitId) })
            .toSortedSet()
        val sourceKnowledge = subjects.associateWith { subject ->
            PayrollLegalSourceKnowledgeStoreV2.knowledgeForMealBasketSubject(
                context = context,
                companyId = companyId,
                idcc = normalizedProfile,
                referenceDate = referenceDate,
                subjectKey = subject
            )
        }

        val arbitration = MealBasketLegalArbitrationBridgeV2.resolve(
            profile = profile.copy(siret = normalizedSiret),
            referenceDate = referenceDate,
            branchRules = branchRules,
            companyRules = companyRules,
            territoryCode = territoryCode,
            sourceKnowledgeBySubject = sourceKnowledge
        )

        val warnings = buildList {
            if (!branchTrusted && storedBranch.isNotEmpty()) {
                add("Panier repas KALI : règle(s) locale(s) présentes mais couverture officielle MEAL_BASKET non exploitable à cette date ; elles sont ignorées.")
            }
            if (!coverage.reliable && companyRules.isEmpty()) addAll(coverage.warnings)
            addAll(arbitration.warnings)
        }.distinct()

        return Snapshot(
            resolution = arbitration.copy(warnings = warnings),
            branchCoverage = coverage,
            branchRulesLoaded = branchRules.size,
            companyRulesLoaded = companyRules.size,
            warnings = warnings
        )
    }

    private fun blocked(reason: String): Snapshot {
        val warnings = listOf("Panier repas ACCO/KALI : $reason ; calcul automatique bloqué.")
        return Snapshot(
            resolution = MealBasketLegalArbitrationBridgeV2.Result(
                selected = emptyList(),
                reliable = false,
                warnings = warnings
            ),
            branchCoverage = null,
            branchRulesLoaded = 0,
            companyRulesLoaded = 0,
            warnings = warnings
        )
    }
}