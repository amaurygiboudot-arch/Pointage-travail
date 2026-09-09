package com.amaury.pointage.v2

import android.content.Context

/** Raccord prudent entre `/consult/acco` vérifié par SIRET et le store local des paniers repas. */
object CompanyAgreementMealBasketIngestionV2 {
    data class StructuredPackage(
        val detected: Boolean,
        val rules: List<OfficialAccoMealBasketParserV2.Rule>,
        val packageComplete: Boolean,
        val subjects: Set<String>,
        val warnings: List<String>
    ) {
        val structured: Boolean get() = rules.isNotEmpty()
    }

    data class Result(
        val detected: Boolean,
        val structured: Boolean,
        val legalPackageComplete: Boolean,
        val storageComplete: Boolean,
        val savedCount: Int,
        val ruleCount: Int,
        val subjects: Set<String>,
        val warnings: List<String>
    ) {
        val packageComplete: Boolean get() = legalPackageComplete && storageComplete
        val storageFailure: Boolean get() = !storageComplete
    }

    internal fun structure(
        profile: ConventionLegalProfileV2,
        agreementId: String,
        verifiedContent: OfficialAgreementContentParserV2.VerifiedContent
    ): StructuredPackage {
        val normalizedText = OfficialKaliProfileMatcherV2.normalize(verifiedContent.text)
        val detected = dedicatedMealMarkerRegex.containsMatchIn(normalizedText)
        if (!detected) {
            return StructuredPackage(
                detected = false,
                rules = emptyList(),
                packageComplete = false,
                subjects = emptySet(),
                warnings = emptyList()
            )
        }

        val expectedSiret = profile.siret.filter(Char::isDigit)
        val verifiedSiret = verifiedContent.siret.filter(Char::isDigit)
        if (expectedSiret.length != 14 || verifiedSiret != expectedSiret) {
            return blockedStructure("SIRET officiel différent du profil local")
        }

        val diagnostic = OfficialAccoMealBasketParserV2.parse(
            profile = profile,
            agreementId = agreementId,
            officialText = verifiedContent.text
        )
        val hardened = diagnostic.rules.map(AccoMealBasketRuleHardeningV2::harden)
        val hardenedRules = hardened.mapNotNull { it.rule }
        val hardeningWarnings = hardened.flatMap { it.warnings }
        val subjects = hardenedRules
            .map { com.amaury.pointage.v2.engine.MealBasketLegalArbitrationBridgeV2.subject(it.benefitId) }
            .toSet()
        val hardeningComplete = hardenedRules.size == diagnostic.rules.size

        if (!diagnostic.fullyStructured || !hardeningComplete) {
            return StructuredPackage(
                detected = true,
                rules = hardenedRules,
                packageComplete = false,
                subjects = subjects + "MEAL_OTHER",
                warnings = (
                    diagnostic.reasons + hardeningWarnings +
                        "ACCO repas : le paquet du profil n'est pas intégralement structuré ; aucune règle partielle ne peut alimenter le calcul."
                    ).distinct()
            )
        }

        return StructuredPackage(
            detected = true,
            rules = hardenedRules,
            packageComplete = true,
            subjects = subjects,
            warnings = (diagnostic.reasons + hardeningWarnings).distinct()
        )
    }

    fun ingestVerified(
        context: Context,
        companyId: String,
        agreementId: String,
        verifiedContent: OfficialAgreementContentParserV2.VerifiedContent
    ): Result {
        val profile = ConventionLegalProfileV2.load(context, companyId)
            ?: return blockedResult("profil juridique local introuvable")

        val structured = structure(profile, agreementId, verifiedContent)
        if (!structured.detected) {
            val rulesCleared = V2CompanyMealBasketStore.removeAgreementPackage(
                context = context,
                companyId = companyId,
                agreementId = agreementId,
                expectedSiret = profile.siret,
                classification = profile.classification,
                professionalStatus = profile.professionalStatus.orEmpty()
            )
            val stateCleared = rulesCleared && V2CompanyMealBasketAuditStateStore.clearAgreement(
                context = context,
                companyId = companyId,
                agreementId = agreementId,
                profile = profile
            )
            val cleaned = rulesCleared && stateCleared
            return Result(
                detected = false,
                structured = false,
                legalPackageComplete = false,
                storageComplete = cleaned,
                savedCount = 0,
                ruleCount = 0,
                subjects = emptySet(),
                warnings = buildList {
                    addAll(structured.warnings)
                    if (!cleaned) add(
                        "ACCO repas : le contenu officiel ne contient plus de panier, mais l'ancien paquet local n'a pas pu être purgé complètement ; audit global bloqué."
                    )
                }.distinct()
            )
        }
        if (!structured.packageComplete) {
            val markerStored = V2CompanyMealBasketAuditStateStore.mark(
                context = context,
                companyId = companyId,
                agreementId = agreementId,
                profile = profile,
                state = V2CompanyMealBasketAuditStateStore.State.UNRESOLVED,
                subjects = structured.subjects + "MEAL_OTHER"
            )
            return Result(
                detected = true,
                structured = structured.structured,
                legalPackageComplete = false,
                storageComplete = markerStored,
                savedCount = 0,
                ruleCount = structured.rules.size,
                subjects = structured.subjects,
                warnings = (structured.warnings + if (markerStored) emptyList() else listOf(
                    "ACCO repas : état d'incertitude non persisté ; stockage local à contrôler."
                )).distinct()
            )
        }

        // Avant de remplacer les règles, l'ancien état COMPLETE est explicitement invalidé. Si
        // l'écriture du paquet ou du nouveau marqueur COMPLETE échoue, UNRESOLVED reste visible et
        // le provider bloque le calcul au lieu de consommer un paquet à moitié mis à jour.
        val pendingStored = V2CompanyMealBasketAuditStateStore.mark(
            context = context,
            companyId = companyId,
            agreementId = agreementId,
            profile = profile,
            state = V2CompanyMealBasketAuditStateStore.State.UNRESOLVED,
            subjects = structured.subjects + "MEAL_OTHER"
        )
        if (!pendingStored) {
            return Result(
                detected = true,
                structured = true,
                legalPackageComplete = true,
                storageComplete = false,
                savedCount = 0,
                ruleCount = structured.rules.size,
                subjects = structured.subjects,
                warnings = (structured.warnings +
                    "ACCO repas : impossible d'invalider l'ancien marqueur avant remplacement ; paquet non écrit.").distinct()
            )
        }

        val stored = V2CompanyMealBasketStore.saveVerifiedPackage(
            context = context,
            companyId = companyId,
            rules = structured.rules
        )
        val stateStored = stored && V2CompanyMealBasketAuditStateStore.mark(
            context = context,
            companyId = companyId,
            agreementId = agreementId,
            profile = profile,
            state = V2CompanyMealBasketAuditStateStore.State.COMPLETE,
            subjects = structured.subjects
        )
        val storageComplete = stored && stateStored
        val warnings = buildList {
            if (!stored) add(
                "ACCO repas : le paquet ${agreementId.trim().uppercase()} n'a pas pu être stocké atomiquement ; l'état UNRESOLVED reste actif."
            )
            if (stored && !stateStored) add(
                "ACCO repas : règles stockées mais marqueur d'audit complet impossible à persister ; l'état UNRESOLVED reste actif et bloque la paie."
            )
        }

        return Result(
            detected = true,
            structured = structured.rules.isNotEmpty(),
            legalPackageComplete = true,
            storageComplete = storageComplete,
            savedCount = if (storageComplete) structured.rules.size else 0,
            ruleCount = structured.rules.size,
            subjects = structured.subjects,
            warnings = (structured.warnings + warnings).distinct()
        )
    }

    private fun blockedStructure(reason: String) = StructuredPackage(
        detected = true,
        rules = emptyList(),
        packageComplete = false,
        subjects = setOf("MEAL_OTHER"),
        warnings = listOf("ACCO repas : $reason ; aucune règle d'entreprise n'est structurée.")
    )

    private fun blockedResult(reason: String) = Result(
        detected = true,
        structured = false,
        legalPackageComplete = false,
        storageComplete = false,
        savedCount = 0,
        ruleCount = 0,
        subjects = setOf("MEAL_OTHER"),
        warnings = listOf("ACCO repas : $reason ; aucune règle d'entreprise n'est enregistrée.")
    )

    private val dedicatedMealMarkerRegex = Regex(
        "\\b(?:paniers?(?: repas| de nuit)?|indemnite(?:s)?(?: de)? repas|allocation(?:s)? de repas|prime(?:s)? de panier)\\b"
    )
}
