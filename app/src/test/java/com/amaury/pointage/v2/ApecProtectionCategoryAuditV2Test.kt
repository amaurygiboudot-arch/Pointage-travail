package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.ConventionProtectionCategoryV2
import com.amaury.pointage.v2.engine.ProtectionCategoryV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ApecProtectionCategoryAuditV2Test {
    private val referenceDate = LocalDate.of(2026, 9, 8)
    private val textId = "KALITEXT000000000001"
    private val formalTitle = "Accord du 27 juin 2024 relatif aux catégories de bénéficiaire du régime de protection sociale complémentaire"

    private fun profile(
        idcc: String = "292",
        status: String = "CADRE",
        coefficient: Int = 910
    ) = ConventionLegalProfileV2(
        companyId = "company",
        idcc = idcc,
        siret = "12345678901234",
        professionalStatus = status,
        classification = ConventionClassificationV2(coefficient = coefficient),
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun kaliRule(
        idcc: String = "292",
        status: String = "CADRE",
        coefficient: Int = 910,
        scope: String = textId,
        effectiveFrom: LocalDate = LocalDate.of(2025, 1, 1)
    ) = ConventionProtectionCategoryV2.Rule(
        idcc = idcc,
        ruleId = "KALI-PROTECTION-CATEGORY-test-$idcc-$status-$coefficient",
        effectiveFrom = effectiveFrom,
        classification = ConventionClassificationV2(coefficient = coefficient),
        professionalStatus = status,
        aniCategory = if (status == "CADRE") {
            ProtectionCategoryV2.AniCategory.ARTICLE_2_1
        } else {
            ProtectionCategoryV2.AniCategory.ARTICLE_2_2
        },
        source = "Légifrance KALI — KALIARTI000050828557",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1),
        conventionScopeKey = scope
    )

    private fun identity() = OfficialKaliTextIdentityV2.Identity(
        textId = textId,
        title = formalTitle,
        agreementDate = LocalDate.of(2024, 6, 27),
        source = "Légifrance KALI — $textId"
    )

    private fun document(
        documentId: String = "APEC-292-2024",
        title: String = formalTitle,
        effectDate: String = "01.01.2025",
        coefficientRange: String = "900 à 940"
    ) = OfficialApecProtectionCategoryParserV2.Document(
        documentId = documentId,
        sourceUrl = "https://commission-paritaire.apec.fr/assets/files/$documentId.pdf",
        content = """
            AGREMENT DU 09.10.2024
            CCN de la plasturgie (IDCC 292)
            $title.
            La Commission paritaire rattachée à l'Apec valide l'affiliation des cadres coefficients $coefficientRange à l'article 2.1 de l'ANI du 17 novembre 2017.
            DATE D'EFFET $effectDate
        """.trimIndent()
    )

    private fun fetched(document: OfficialApecProtectionCategoryParserV2.Document) =
        OfficialApecDecisionClientV2.FetchedDocument(
            candidate = OfficialApecDecisionIndexV2.Candidate(
                idcc = "292",
                documentId = document.documentId,
                sourceUrl = document.sourceUrl,
                linkLabel = "Agrément",
                rowText = "Plasturgie 292"
            ),
            document = document,
            pages = 2
        )

    private fun apec(
        documents: List<OfficialApecDecisionClientV2.FetchedDocument> = listOf(fetched(document())),
        candidatesFound: Int = documents.size,
        complete: Boolean = true
    ) = OfficialApecDecisionClientV2.Result(
        idcc = "292",
        candidatesFound = candidatesFound,
        documents = documents,
        technicalCoverageComplete = complete,
        warnings = emptyList()
    )

    private fun identities(
        complete: Boolean = true,
        values: Map<String, OfficialKaliTextIdentityV2.Identity> = mapOf(textId to identity())
    ) = ApecProtectionCategoryAuditV2.IdentityBatch(
        identities = values,
        complete = complete,
        warnings = emptyList()
    )

    @Test
    fun `seule une règle exacte active du profil est retenue`() {
        val exact = kaliRule()
        val rules = listOf(
            exact,
            kaliRule(idcc = "493"),
            kaliRule(status = "NON_CADRE"),
            kaliRule(coefficient = 920),
            kaliRule(scope = "scope-libre"),
            kaliRule(effectiveFrom = LocalDate.of(2027, 1, 1))
        )

        val selected = ApecProtectionCategoryAuditV2.exactProfileRules(profile(), referenceDate, rules)

        assertEquals(1, selected.size)
        assertEquals(exact.ruleId, selected.single().ruleId)
        assertEquals(textId, selected.single().conventionScopeKey)
    }

    @Test
    fun `KALI APEC complets et document exact donnent une règle fiable`() {
        val evaluation = ApecProtectionCategoryAuditV2.evaluate(
            profile = profile(),
            referenceDate = referenceDate,
            rules = listOf(kaliRule()),
            identities = identities(),
            apec = apec(),
            kaliTechnicalCoverageComplete = true
        )

        assertTrue(evaluation.reliable)
        assertEquals(1, evaluation.approvedRulesFound)
        assertNotNull(evaluation.selectedRule)
        assertEquals(
            ConventionProtectionCategoryV2.ApprovalStatus.APEC_APPROVED,
            evaluation.selectedRule!!.approvalStatus
        )
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, evaluation.selectedRule!!.aniCategory)
    }

    @Test
    fun `un candidat APEC non lu bloque même si un document correspond`() {
        val evaluation = ApecProtectionCategoryAuditV2.evaluate(
            profile = profile(),
            referenceDate = referenceDate,
            rules = listOf(kaliRule()),
            identities = identities(),
            apec = apec(candidatesFound = 2, complete = false),
            kaliTechnicalCoverageComplete = true
        )

        assertFalse(evaluation.reliable)
        assertEquals(0, evaluation.approvedRulesFound)
        assertNull(evaluation.selectedRule)
        assertTrue(evaluation.warnings.any { it.contains("tous les agréments", ignoreCase = true) })
    }

    @Test
    fun `identité KALITEXT incomplète bloque avant tout agrément`() {
        val evaluation = ApecProtectionCategoryAuditV2.evaluate(
            profile = profile(),
            referenceDate = referenceDate,
            rules = listOf(kaliRule()),
            identities = identities(complete = false, values = emptyMap()),
            apec = apec(),
            kaliTechnicalCoverageComplete = true
        )

        assertFalse(evaluation.reliable)
        assertNull(evaluation.selectedRule)
        assertTrue(evaluation.warnings.any { it.contains("identité KALITEXT", ignoreCase = true) })
    }

    @Test
    fun `couverture KALI incomplète bloque même avec APEC parfait`() {
        val evaluation = ApecProtectionCategoryAuditV2.evaluate(
            profile = profile(),
            referenceDate = referenceDate,
            rules = listOf(kaliRule()),
            identities = identities(),
            apec = apec(),
            kaliTechnicalCoverageComplete = false
        )

        assertFalse(evaluation.reliable)
        assertNull(evaluation.selectedRule)
        assertTrue(evaluation.warnings.any { it.contains("couverture KALI", ignoreCase = true) })
    }

    @Test
    fun `DATE D EFFET APEC future conserve la preuve mais ne débloque pas la date actuelle`() {
        val future = document(effectDate = "01.01.2027")
        val evaluation = ApecProtectionCategoryAuditV2.evaluate(
            profile = profile(),
            referenceDate = referenceDate,
            rules = listOf(kaliRule()),
            identities = identities(),
            apec = apec(documents = listOf(fetched(future))),
            kaliTechnicalCoverageComplete = true
        )

        assertFalse(evaluation.reliable)
        assertEquals(1, evaluation.approvedRulesFound)
        assertNull(evaluation.selectedRule)
        assertTrue(evaluation.warnings.any { it.contains("agrément APEC", ignoreCase = true) })
    }

    @Test
    fun `titre APEC voisin ne se rattache jamais au KALITEXT exact`() {
        val nearby = document(title = "Accord du 27 juin 2024 relatif à la prévoyance")
        val evaluation = ApecProtectionCategoryAuditV2.evaluate(
            profile = profile(),
            referenceDate = referenceDate,
            rules = listOf(kaliRule()),
            identities = identities(),
            apec = apec(documents = listOf(fetched(nearby))),
            kaliTechnicalCoverageComplete = true
        )

        assertFalse(evaluation.reliable)
        assertEquals(0, evaluation.approvedRulesFound)
        assertNull(evaluation.selectedRule)
    }

    @Test
    fun `un document APEC non pertinent peut coexister si tous les documents ont été lus`() {
        val exact = fetched(document(documentId = "exact"))
        val irrelevant = fetched(
            document(
                documentId = "autre-champ",
                coefficientRange = "950 à 980"
            )
        )
        val evaluation = ApecProtectionCategoryAuditV2.evaluate(
            profile = profile(),
            referenceDate = referenceDate,
            rules = listOf(kaliRule()),
            identities = identities(),
            apec = apec(documents = listOf(exact, irrelevant), candidatesFound = 2, complete = true),
            kaliTechnicalCoverageComplete = true
        )

        assertTrue(evaluation.reliable)
        assertEquals(1, evaluation.approvedRulesFound)
        assertNotNull(evaluation.selectedRule)
    }
}
