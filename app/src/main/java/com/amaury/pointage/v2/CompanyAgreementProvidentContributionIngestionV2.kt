package com.amaury.pointage.v2

import android.content.Context

/**
 * Raccord prudent entre une consultation ACCO déjà vérifiée par SIRET et le store local de
 * cotisations de prévoyance structurées. Une simple détection de mots-clés ne suffit jamais :
 * le parseur juridique complet doit produire une règle exacte avant toute sauvegarde.
 */
object CompanyAgreementProvidentContributionIngestionV2 {
    data class Structured(
        val detected: Boolean,
        val rule: OfficialAccoProvidentContributionParserV2.Rule?,
        val warnings: List<String>
    )

    data class Result(
        val detected: Boolean,
        val structured: Boolean,
        val saved: Boolean,
        val rule: OfficialAccoProvidentContributionParserV2.Rule?,
        val warnings: List<String>
    ) {
        val storageFailure: Boolean get() = structured && !saved
    }

    fun ingestVerified(
        context: Context,
        companyId: String,
        agreementId: String,
        verifiedContent: OfficialAgreementContentParserV2.VerifiedContent
    ): Result {
        val detected = detectsContribution(verifiedContent.text)
        if (!detected) {
            return Result(false, false, false, null, emptyList())
        }
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return Result(
                detected = true,
                structured = false,
                saved = false,
                rule = null,
                warnings = listOf("ACCO prévoyance : clause détectée mais profil juridique local introuvable ; aucune règle n'est enregistrée.")
            )
        val structured = structure(profile, agreementId, verifiedContent)
        val rule = structured.rule
        if (rule == null) {
            return Result(
                detected = structured.detected,
                structured = false,
                saved = false,
                rule = null,
                warnings = structured.warnings
            )
        }
        val saved = V2CompanyProvidentContributionStore.saveVerified(context, companyId, rule)
        return Result(
            detected = true,
            structured = true,
            saved = saved,
            rule = rule,
            warnings = buildList {
                addAll(structured.warnings)
                if (!saved) {
                    add("ACCO prévoyance : règle structurée mais stockage local impossible ; elle ne sera pas utilisée.")
                }
            }.distinct()
        )
    }

    internal fun structure(
        profile: ConventionLegalProfileV2,
        agreementId: String,
        verifiedContent: OfficialAgreementContentParserV2.VerifiedContent
    ): Structured {
        if (!detectsContribution(verifiedContent.text)) return Structured(false, null, emptyList())

        val expectedSiret = profile.siret.filter(Char::isDigit)
        val verifiedSiret = verifiedContent.siret.filter(Char::isDigit)
        if (expectedSiret.length != 14 || verifiedSiret.length != 14 || expectedSiret != verifiedSiret) {
            return Structured(
                detected = true,
                rule = null,
                warnings = listOf("ACCO prévoyance : le SIRET vérifié ne correspond pas au profil local ; règle rejetée.")
            )
        }

        val diagnostic = OfficialAccoProvidentContributionParserV2.parse(
            profile = profile,
            agreementId = agreementId,
            officialText = verifiedContent.text
        )
        return Structured(
            detected = true,
            rule = diagnostic.rule,
            warnings = diagnostic.reasons
        )
    }

    private fun detectsContribution(text: String): Boolean =
        CompanyAgreementRuleExtractorV2.extract(text)
            .any { it.category == CompanyAgreementRuleExtractorV2.Category.PROVIDENT_CONTRIBUTION }
}
