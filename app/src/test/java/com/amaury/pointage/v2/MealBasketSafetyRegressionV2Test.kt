package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMealBasketV2
import com.amaury.pointage.v2.engine.ConventionMinimumSalaryV2
import com.amaury.pointage.v2.engine.MealBasketLegalArbitrationBridgeV2
import com.amaury.pointage.v2.engine.PayrollLegalArbitratorV2
import com.amaury.pointage.v2.engine.VerifiedMealBasketPayrollV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MealBasketSafetyRegressionV2Test {
    private val date = LocalDate.of(2026, 9, 8)
    private val zone = ZoneId.of("Europe/Paris")

    private fun profile(employment: String? = null) = ConventionLegalProfileV2(
        companyId = "company",
        idcc = "292",
        siret = "12345678901234",
        professionalStatus = "NON_CADRE",
        classification = ConventionClassificationV2(coefficient = 700, employment = employment),
        contractType = "FULL_TIME",
        entryDate = LocalDate.of(2020, 1, 1),
        conventionSeniorityDate = null,
        weeklyHours = 35.0,
        forfaitAnnualHours = null,
        forfaitAnnualDays = null
    )

    private fun agreement(body: String) =
        "Le présent accord prend effet le 1 septembre 2026 et est conclu pour une durée indéterminée. $body"

    private fun article(content: String) = OfficialKaliOvertimeRuleParserV2.VerifiedArticle(
        articleId = "KALIARTI000000009901",
        status = "VIGUEUR_ETEN",
        content = content,
        effectiveFrom = LocalDate.of(2025, 1, 1),
        effectiveTo = null,
        title = "Paniers repas",
        extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
    )

    @Test
    fun `panier de nuit explicite ACCO reste un objet nuit sans fenetre horaire`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(),
            "ACCOTEXT000000000901",
            agreement("Coefficient 700 non-cadres. Panier de nuit de 8,50 € par journée travaillée.")
        )
        assertTrue(result.rules.single().benefitId.startsWith("MEAL_NIGHT_"))
    }

    @Test
    fun `panier de nuit explicite KALI reste un objet nuit sans fenetre horaire`() {
        val result = OfficialKaliMealBasketParserV2.parse(
            profile = profile(),
            verifiedIdcc = "292",
            auditDate = date,
            articles = listOf(article("Coefficient 700 non-cadres. Panier de nuit de 8,50 € par journée travaillée.")),
            articleTextIds = mapOf("KALIARTI000000009901" to "KALITEXT000000009901")
        )
        assertTrue(result.rules.single().benefitId.startsWith("MEAL_NIGHT_"))
    }

    @Test
    fun `condition ACCO de debut seule est bloquee au lieu de devenir debut ou fin`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(),
            "ACCOTEXT000000000902",
            agreement("Coefficient 700 non-cadres. Panier repas de 8,50 € lorsque le poste commence entre 21 h et 23 h.")
        )
        assertTrue(result.rules.isEmpty())
        assertTrue(result.unresolvedOccurrences > 0)
    }

    @Test
    fun `exclusion professionnelle ACCO non structuree bloque le panier`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(employment = "gardien"),
            "ACCOTEXT000000000903",
            agreement("Non-cadres. Panier repas de 6,25 € par journée travaillée, sauf les gardiens.")
        )
        assertTrue(result.rules.isEmpty())
        assertTrue(result.unresolvedOccurrences > 0)
    }

    @Test
    fun `travail poste ACCO ne devient pas un faux selecteur de classification`() {
        val result = OfficialAccoMealBasketParserV2.parse(
            profile(),
            "ACCOTEXT000000000904",
            agreement("Non-cadres. Panier repas de 6,25 € par journée travaillée pour le personnel posté.")
        )
        assertTrue(result.rules.isNotEmpty())
        assertTrue(
            result.rules.single().eligibilityAnyOf.single().allOf
                .contains(ConventionMealBasketV2.Condition.PostedShiftWorker)
        )
    }

    @Test
    fun `filiation KALI incomplete interdit la certification du paquet`() {
        val verified = article("Coefficient 700 non-cadres. Panier repas de 6,25 € par journée travaillée.")
        val evidence = KaliMatterEvidenceAuditV2.Evidence(
            idcc = "292",
            referenceDate = date,
            expressions = listOf("panier repas"),
            pagesRead = 1,
            candidates = 1,
            textsConsulted = 0,
            unresolvedSections = 0,
            articlesConsulted = 1,
            searchCoverageComplete = true,
            allTextsExpanded = true,
            allArticlesConsulted = true,
            articles = listOf(verified),
            articleTextIds = emptyMap(),
            ambiguousArticleTextIds = emptySet(),
            warnings = emptyList()
        )
        assertFalse(KaliMealBasketAuditV2.articleLineageComplete(evidence))

        val diagnostic = OfficialKaliMealBasketParserV2.parse(
            profile = profile(),
            verifiedIdcc = "292",
            auditDate = date,
            articles = listOf(verified),
            articleTextIds = mapOf(verified.articleId to "KALITEXT000000009901")
        )
        val completion = KaliMealBasketAuditV2.evaluateCompletion(
            technicalCoverageComplete = true,
            diagnostic = diagnostic,
            savedRules = diagnostic.rules.size,
            referenceDate = date,
            articleLineageComplete = false
        )
        assertFalse(completion.completed)
    }

    @Test
    fun `pause terminee mais encore a confirmer bloque le calcul panier`() {
        val rule = ConventionMealBasketV2.Rule(
            idcc = "292",
            ruleId = "KALI-MEAL-V2C-KALITEXT000000009901-KALIARTI000000009901-1",
            benefitId = "MEAL_DAY_1",
            effectiveFrom = LocalDate.of(2025, 1, 1),
            classification = ConventionClassificationV2(coefficient = 700),
            professionalStatus = "NON_CADRE",
            deliveryMode = ConventionMealBasketV2.DeliveryMode.CASH_ALLOWANCE,
            amountFormula = ConventionMealBasketV2.AmountFormula.FixedEuro(6.25),
            eligibilityAnyOf = listOf(
                ConventionMealBasketV2.EligibilityGroup(listOf(ConventionMealBasketV2.Condition.WorkedDay))
            ),
            countingUnit = ConventionMealBasketV2.CountingUnit.WORKED_DAY,
            source = "Légifrance KALI",
            conventionScopeKey = "KALITEXT000000009901",
            evidenceArticleIds = setOf("KALIARTI000000009901"),
            extensionStatus = ConventionMinimumSalaryV2.ExtensionStatus.EXTENDED,
            extensionEffectiveFrom = LocalDate.of(2025, 1, 1)
        )
        val arbitration = MealBasketLegalArbitrationBridgeV2.Result(
            selected = listOf(
                MealBasketLegalArbitrationBridgeV2.Selected(
                    subject = "MEAL_DAY",
                    source = PayrollLegalArbitratorV2.Source.KALI,
                    branchRule = rule
                )
            ),
            reliable = true,
            warnings = emptyList()
        )
        fun ms(hour: Int): Long = LocalDateTime.of(2026, 9, 8, hour, 0).atZone(zone).toInstant().toEpochMilli()
        val pause = PauseV2(
            startMs = ms(12),
            endMs = ms(13),
            paid = false,
            source = EventSourceV2.MANUAL,
            status = DecisionStatusV2.TO_CONFIRM
        )
        val session = WorkSessionV2(
            id = "unconfirmed-pause",
            employerId = "employer",
            realArrivalMs = ms(8),
            countedEntryMs = ms(8),
            countedExitMs = ms(16),
            realExitMs = ms(16),
            pauses = listOf(pause),
            status = SessionStatusV2.CLOSED
        )
        val result = VerifiedMealBasketPayrollV2.calculate(
            sessions = listOf(session),
            year = 2026,
            monthZeroBased = 8,
            acceptedEmployerIds = setOf("employer"),
            arbitration = arbitration,
            zoneId = zone
        )
        assertFalse(result.reliable)
        assertNull(result.totalAmount)
    }
}
