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

class ProtectionCategoryApprovalPipelineV2Test {
    private val referenceDate = LocalDate.of(2026, 9, 8)
    private val textId = "KALITEXT000000000001"
    private val formalTitle = "Accord du 27 juin 2024 relatif aux catégories de bénéficiaire du régime de protection sociale complémentaire"

    private fun profile(
        idcc: String = "292",
        status: String = "CADRE",
        classification: ConventionClassificationV2 = ConventionClassificationV2(coefficient = 910)
    ) = ConventionLegalProfileV2(
        companyId = "c1",
        idcc = idcc,
        siret = "12345678901234",
        professionalStatus = status,
        classification = classification,
        contractType = "CDI",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = LocalDate.of(2020, 1, 1),
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun kaliRule(
        extensionFrom: LocalDate = LocalDate.of(2025, 1, 1),
        classification: ConventionClassificationV2 = ConventionClassificationV2(coefficient = 910),
        status: String = "CADRE",
        category: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1
    ) = ConventionProtectionCategoryV2.Rule(
        idcc = "292",
        ruleId = "KALI-PROTECTION-CATEGORY-test",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = classification,
        professionalStatus = status,
        aniCategory = category,
        source = "Légifrance KALI — KALIARTI000050828557",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = extensionFrom,
        conventionScopeKey = textId
    )

    private fun identity(
        date: LocalDate? = LocalDate.of(2024, 6, 27),
        title: String? = formalTitle
    ) = OfficialKaliTextIdentityV2.Identity(
        textId = textId,
        title = title,
        agreementDate = date,
        source = "Légifrance KALI — $textId"
    )

    private fun document(
        effectDate: String = "01.01.2025",
        title: String = formalTitle,
        idcc: String = "292",
        coefficient: String = "900 à 940"
    ) = OfficialApecProtectionCategoryParserV2.Document(
        documentId = "APEC-2024-10-09-IDCC292",
        sourceUrl = "https://commission-paritaire.apec.fr/assets/files/agrement-plasturgie.pdf",
        content = """
            AGREMENT DU 09.10.2024
            CCN de la plasturgie (IDCC $idcc)
            $title.
            La Commission paritaire rattachée à l'Apec valide l'affiliation des cadres coefficients $coefficient à l'article 2.1 de l'ANI du 17 novembre 2017.
            DATE D'EFFET $effectDate
        """.trimIndent()
    )

    @Test
    fun `chaîne exacte KALI APEC produit une catégorie fiable à la date auditée`() {
        val result = ProtectionCategoryApprovalPipelineV2.resolve(
            profile(), referenceDate, kaliRule(), identity(), document()
        )

        assertTrue(result.reliable)
        assertNotNull(result.approvedRule)
        assertEquals(ConventionProtectionCategoryV2.ApprovalStatus.APEC_APPROVED, result.approvedRule!!.approvalStatus)
        assertEquals(ProtectionCategoryV2.AniCategory.ARTICLE_2_1, result.resolution.category.aniCategory)
        assertTrue(result.resolution.category.confirmed)
    }

    @Test
    fun `extension KALI future bloque la catégorie actuelle malgré APEC complet`() {
        val result = ProtectionCategoryApprovalPipelineV2.resolve(
            profile(),
            referenceDate,
            kaliRule(extensionFrom = LocalDate.of(2027, 1, 1)),
            identity(),
            document()
        )

        assertFalse(result.reliable)
        assertNotNull(result.approvedRule)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.resolution.category.aniCategory)
    }

    @Test
    fun `DATE D EFFET APEC future conserve la preuve mais bloque la catégorie actuelle`() {
        val result = ProtectionCategoryApprovalPipelineV2.resolve(
            profile(), referenceDate, kaliRule(), identity(), document(effectDate = "01.01.2027")
        )

        assertFalse(result.reliable)
        assertNotNull(result.approvedRule)
        assertEquals(ProtectionCategoryV2.AniCategory.TO_CONFIRM, result.resolution.category.aniCategory)
    }

    @Test
    fun `absence de DATE D EFFET APEC ne produit aucune règle approuvée`() {
        val withoutEffect = OfficialApecProtectionCategoryParserV2.Document(
            documentId = "APEC-2024-10-09-IDCC292",
            sourceUrl = "https://commission-paritaire.apec.fr/assets/files/agrement-plasturgie.pdf",
            content = """
                AGREMENT DU 09.10.2024
                CCN de la plasturgie (IDCC 292)
                $formalTitle.
                La Commission paritaire rattachée à l'Apec valide l'affiliation des cadres coefficients 900 à 940 à l'article 2.1 de l'ANI du 17 novembre 2017.
            """.trimIndent()
        )
        val result = ProtectionCategoryApprovalPipelineV2.resolve(
            profile(), referenceDate, kaliRule(), identity(), withoutEffect
        )

        assertFalse(result.reliable)
        assertNull(result.approvedRule)
        assertTrue(result.warnings.any { it.contains("date d'applicabilité") || it.contains("DATE D'EFFET") })
    }

    @Test
    fun `titre KALITEXT non repris exactement par APEC bloque toute approbation`() {
        val result = ProtectionCategoryApprovalPipelineV2.resolve(
            profile(),
            referenceDate,
            kaliRule(),
            identity(),
            document(title = "Accord du 27 juin 2024 relatif à la prévoyance")
        )

        assertFalse(result.reliable)
        assertNull(result.approvedRule)
    }

    @Test
    fun `profil salarié différent de la preuve KALI bloque avant APEC`() {
        val result = ProtectionCategoryApprovalPipelineV2.resolve(
            profile(classification = ConventionClassificationV2(coefficient = 920)),
            referenceDate,
            kaliRule(classification = ConventionClassificationV2(coefficient = 910)),
            identity(),
            document()
        )

        assertFalse(result.reliable)
        assertNull(result.approvedRule)
        assertTrue(result.warnings.any { it.contains("classification") })
    }

    @Test
    fun `IDCC APEC différent bloque la chaîne même avec texte identique`() {
        val result = ProtectionCategoryApprovalPipelineV2.resolve(
            profile(), referenceDate, kaliRule(), identity(), document(idcc = "493")
        )

        assertFalse(result.reliable)
        assertNull(result.approvedRule)
    }

    @Test
    fun `identité KALITEXT incomplète bloque le rapprochement inter-sources`() {
        val result = ProtectionCategoryApprovalPipelineV2.resolve(
            profile(), referenceDate, kaliRule(), identity(date = null), document()
        )

        assertFalse(result.reliable)
        assertNull(result.approvedRule)
    }
}
