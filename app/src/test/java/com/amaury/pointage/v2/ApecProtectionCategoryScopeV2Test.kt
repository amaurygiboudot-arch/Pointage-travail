package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ApecProtectionCategoryApprovalV2
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

class ApecProtectionCategoryScopeV2Test {
    private val textId = "KALITEXT000000000001"
    private val formalTitle = "Accord du 27 juin 2024 relatif aux catégories de bénéficiaire du régime de protection sociale complémentaire"
    private val classification = ConventionClassificationV2(coefficient = 910)

    private fun kaliRule(
        scope: String? = textId,
        selector: ConventionClassificationV2 = classification,
        category: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        status: String = "CADRE"
    ) = ConventionProtectionCategoryV2.Rule(
        idcc = "292",
        ruleId = "KALI-PROTECTION-CATEGORY-test",
        effectiveFrom = LocalDate.of(2025, 1, 1),
        classification = selector,
        professionalStatus = status,
        aniCategory = category,
        source = "Légifrance KALI — KALIARTI000050828557",
        extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1),
        conventionScopeKey = scope
    )

    private fun identity(
        id: String = textId,
        title: String? = formalTitle,
        date: LocalDate? = LocalDate.of(2024, 6, 27)
    ) = OfficialKaliTextIdentityV2.Identity(
        textId = id,
        title = title,
        agreementDate = date,
        source = "Légifrance KALI — $id"
    )

    private fun document(
        id: String = "APEC-2024-10-09-IDCC292",
        source: String = "https://commission-paritaire.apec.fr/assets/files/agrement-plasturgie.pdf",
        titleInBody: String = formalTitle
    ) = OfficialApecProtectionCategoryParserV2.Document(
        documentId = id,
        sourceUrl = source,
        content = """
            AGREMENT DU 09.10.2024
            CCN de la plasturgie (IDCC 292)
            $titleInBody.
            La Commission paritaire rattachée à l'Apec valide l'affiliation des cadres coefficients 900 à 940 à l'article 2.1 de l'ANI du 17 novembre 2017.
            DATE D'EFFET 01.01.2025
        """.trimIndent()
    )

    private fun evidence(
        id: String = "APEC-2024-10-09-IDCC292",
        source: String = "https://commission-paritaire.apec.fr/assets/files/agrement-plasturgie.pdf",
        idcc: String = "292",
        selector: ConventionClassificationV2 = classification,
        status: String = "CADRE",
        category: ProtectionCategoryV2.AniCategory = ProtectionCategoryV2.AniCategory.ARTICLE_2_1,
        applicableFrom: LocalDate? = LocalDate.of(2025, 1, 1)
    ) = OfficialApecProtectionCategoryParserV2.Evidence(
        documentId = id,
        idcc = idcc,
        decisionDate = LocalDate.of(2024, 10, 9),
        applicableFrom = applicableFrom,
        requestedEffectiveFrom = null,
        classification = selector,
        professionalStatus = status,
        aniCategory = category,
        agreementReferences = listOf(formalTitle),
        source = source
    )

    @Test
    fun `titre KALITEXT exact permet de construire une décision APEC scoped`() {
        val result = ApecProtectionCategoryScopeV2.bind(
            kaliRule(),
            identity(),
            evidence(),
            document()
        )

        assertTrue(result.reliable)
        assertNotNull(result.decision)
        assertEquals(textId, result.decision!!.scopeKey)
        assertEquals(LocalDate.of(2025, 1, 1), result.decision!!.applicableFrom)
    }

    @Test
    fun `la décision scoped exacte peut ensuite approuver la règle KALI`() {
        val scoped = ApecProtectionCategoryScopeV2.bind(kaliRule(), identity(), evidence(), document())
        val approved = ApecProtectionCategoryApprovalV2.approve(kaliRule(), scoped.decision!!)

        assertTrue(approved.reliable)
        assertEquals(ConventionProtectionCategoryV2.ApprovalStatus.APEC_APPROVED, approved.rule?.approvalStatus)
        assertEquals(textId, approved.rule?.approvalScopeKey)
    }

    @Test
    fun `titre APEC paraphrasé ne suffit jamais au rapprochement`() {
        val result = ApecProtectionCategoryScopeV2.bind(
            kaliRule(),
            identity(),
            evidence(),
            document(titleInBody = "Accord du 27 juin 2024 sur la protection sociale complémentaire")
        )

        assertFalse(result.reliable)
        assertNull(result.decision)
        assertTrue(result.warnings.any { it.contains("référence formelle exacte") })
    }

    @Test
    fun `identité KALI sans date accord reste bloquée`() {
        val result = ApecProtectionCategoryScopeV2.bind(
            kaliRule(),
            identity(date = null),
            evidence(),
            document()
        )

        assertFalse(result.reliable)
        assertNull(result.decision)
    }

    @Test
    fun `règle rattachée à un autre KALITEXT reste bloquée`() {
        val result = ApecProtectionCategoryScopeV2.bind(
            kaliRule(scope = "KALITEXT000000000002"),
            identity(),
            evidence(),
            document()
        )

        assertFalse(result.reliable)
        assertNull(result.decision)
    }

    @Test
    fun `classification APEC voisine reste bloquée`() {
        val result = ApecProtectionCategoryScopeV2.bind(
            kaliRule(),
            identity(),
            evidence(selector = ConventionClassificationV2(coefficient = 900)),
            document()
        )

        assertFalse(result.reliable)
        assertNull(result.decision)
    }

    @Test
    fun `catégorie APEC différente reste bloquée`() {
        val result = ApecProtectionCategoryScopeV2.bind(
            kaliRule(),
            identity(),
            evidence(category = ProtectionCategoryV2.AniCategory.ARTICLE_2_2, status = "NON_CADRE"),
            document()
        )

        assertFalse(result.reliable)
        assertNull(result.decision)
    }

    @Test
    fun `IDCC APEC différent reste bloqué`() {
        val result = ApecProtectionCategoryScopeV2.bind(
            kaliRule(),
            identity(),
            evidence(idcc = "493"),
            document()
        )

        assertFalse(result.reliable)
        assertNull(result.decision)
    }

    @Test
    fun `preuve et document APEC de sources différentes restent bloqués`() {
        val result = ApecProtectionCategoryScopeV2.bind(
            kaliRule(),
            identity(),
            evidence(source = "https://commission-paritaire.apec.fr/assets/files/autre.pdf"),
            document()
        )

        assertFalse(result.reliable)
        assertNull(result.decision)
    }

    @Test
    fun `absence de DATE D EFFET laisse le scope prouvé mais bloque l approbation finale`() {
        val scoped = ApecProtectionCategoryScopeV2.bind(
            kaliRule(),
            identity(),
            evidence(applicableFrom = null),
            document()
        )

        assertTrue(scoped.reliable)
        assertNotNull(scoped.decision)
        assertNull(scoped.decision!!.applicableFrom)
        assertTrue(scoped.warnings.any { it.contains("DATE D'EFFET") })

        val approved = ApecProtectionCategoryApprovalV2.approve(kaliRule(), scoped.decision!!)
        assertFalse(approved.reliable)
        assertNull(approved.rule)
        assertTrue(approved.warnings.any { it.contains("date d'applicabilité") })
    }
}
