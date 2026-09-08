package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.ConventionProvidentBenefitV2

/** Raccord prudent entre une consultation ACCO vérifiée par SIRET et le store local des garanties. */
object CompanyAgreementProvidentBenefitIngestionV2 {
    data class Result(
        val detected: Boolean,
        val structured: Boolean,
        val packageComplete: Boolean,
        val structuredCount: Int,
        val savedCount: Int,
        val observedFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val unresolvedFamilies: Set<ConventionProvidentBenefitV2.Family>,
        val warnings: List<String>
    ) {
        val storageFailure: Boolean get() = structuredCount > savedCount
    }

    fun ingestVerified(
        context: Context,
        companyId: String,
        agreementId: String,
        verifiedContent: OfficialAgreementContentParserV2.VerifiedContent
    ): Result {
        val detected = detectsBenefits(verifiedContent.text)
        if (!detected) return Result(false, false, false, 0, 0, emptySet(), emptySet(), emptyList())

        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return unresolved("profil juridique local introuvable")
        val expectedSiret = profile.siret.filter(Char::isDigit)
        val verifiedSiret = verifiedContent.siret.filter(Char::isDigit)
        if (expectedSiret.length != 14 || verifiedSiret.length != 14 || expectedSiret != verifiedSiret) {
            return unresolved("le SIRET vérifié ne correspond pas au profil local")
        }

        val diagnostic = OfficialAccoProvidentBenefitParserV2.parse(
            profile = profile,
            agreementId = agreementId,
            officialText = verifiedContent.text
        )
        var saved = 0
        diagnostic.rules.forEach { rule ->
            if (V2CompanyProvidentBenefitStore.saveVerified(context, companyId, rule)) saved++
        }
        val complete = diagnostic.rules.isNotEmpty() &&
            diagnostic.unresolvedOccurrenceFamilies.isEmpty() &&
            diagnostic.rules.all { it.packageComplete }

        return Result(
            detected = true,
            structured = diagnostic.rules.isNotEmpty(),
            packageComplete = complete,
            structuredCount = diagnostic.rules.size,
            savedCount = saved,
            observedFamilies = diagnostic.observedFamilies,
            unresolvedFamilies = diagnostic.unresolvedOccurrenceFamilies,
            warnings = buildList {
                addAll(diagnostic.reasons)
                if (saved < diagnostic.rules.size) {
                    add("ACCO garanties : ${diagnostic.rules.size - saved} règle(s) structurée(s) n'ont pas pu être stockées localement.")
                }
                if (!complete) {
                    add("ACCO garanties : le paquet ne peut pas servir de preuve d'équivalence L2253-1 tant qu'il reste incomplet.")
                }
            }.distinct()
        )
    }

    private fun detectsBenefits(text: String): Boolean = CompanyAgreementRuleExtractorV2.extract(text)
        .any { it.category == CompanyAgreementRuleExtractorV2.Category.PROVIDENT_BENEFITS }

    private fun unresolved(reason: String) = Result(
        detected = true,
        structured = false,
        packageComplete = false,
        structuredCount = 0,
        savedCount = 0,
        observedFamilies = emptySet(),
        unresolvedFamilies = emptySet(),
        warnings = listOf("ACCO garanties : $reason ; aucune garantie d'entreprise n'est enregistrée.")
    )
}
