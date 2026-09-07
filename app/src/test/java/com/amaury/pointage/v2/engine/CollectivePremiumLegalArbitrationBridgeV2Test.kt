package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.CompanyAgreementRuleExtractorV2
import com.amaury.pointage.v2.CompanyAgreementRuleStoreV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CollectivePremiumLegalArbitrationBridgeV2Test {
    private val date = LocalDate.of(2026, 9, 30)

    private fun companyRule(
        category: CompanyAgreementRuleExtractorV2.Category,
        excerpt: String,
        agreementId: String = "ACCOTEXT1"
    ): CompanyAgreementStructuredRuleV2.Rule = CompanyAgreementStructuredRuleV2.structure(
        CompanyAgreementRuleStoreV2.StoredCandidate(
            agreementId = agreementId,
            category = category,
            excerpt = excerpt,
            confidence = 0.9,
            verified = true,
            effectiveFrom = "2026-01-01",
            effectiveTo = null,
            scope = "Tous les salariés",
            calculationValueVerified = true
        )
    )

    private fun companySnapshot(
        rules: List<CompanyAgreementStructuredRuleV2.Rule> = emptyList()
    ) = CompanyAgreementPayrollBridgeV2.Snapshot(
        referenceDate = date,
        applicableRules = rules.map { it.source },
        calculationReadyRules = rules,
        overtimePercentRules = emptyList(),
        overtimeRules = emptyList(),
        safeOvertimeRules = emptyList(),
        conflictingOvertimeRules = emptyList()
    )

    private fun saturdayKali(multiplier: Double = 1.25) = ConventionWeekdayPremiumSnapshotV2(
        idcc = "0292",
        versionId = "KALI-SATURDAY-X",
        sourceId = "legifrance:KALI:KALIARTIX",
        effectiveFromEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
        effectiveToEpochDay = null,
        rule = WeekdayPremiumRuleV2(WeekdayPremiumKindV2.SATURDAY, multiplier),
        checkedAtMs = 1L
    )

    private fun holidayKali(multiplier: Double = 1.5) = ConventionPublicHolidayPremiumSnapshotV2(
        idcc = "0292",
        versionId = "KALI-PUBLIC-HOLIDAY-X",
        sourceId = "legifrance:KALI:KALIARTIHOLIDAY",
        effectiveFromEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
        effectiveToEpochDay = null,
        rule = PublicHolidayPremiumRuleV2(multiplier),
        checkedAtMs = 1L
    )

    @Test
    fun `KALI seul reste bloque tant que ACCO est inconnu`() {
        val selection = CollectivePremiumLegalArbitrationBridgeV2.resolveWeekday(
            company = companySnapshot(),
            branch = saturdayKali(),
            kind = WeekdayPremiumKindV2.SATURDAY,
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, selection.resolution.state)
        assertNull(selection.selectedRule)
    }

    @Test
    fun `accord entreprise valide prime sur KALI`() {
        val acco = companyRule(
            CompanyAgreementRuleExtractorV2.Category.SATURDAY,
            "Les heures travaillées le samedi donnent lieu à une majoration de 50 %."
        )
        val selection = CollectivePremiumLegalArbitrationBridgeV2.resolveWeekday(
            company = companySnapshot(listOf(acco)),
            branch = saturdayKali(1.25),
            kind = WeekdayPremiumKindV2.SATURDAY,
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, selection.resolution.state)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, selection.resolution.selected!!.source)
        assertEquals(1.5, selection.selectedRule!!.multiplier, 0.0001)
    }

    @Test
    fun `deux ACCO incompatibles bloquent le calcul`() {
        val one = companyRule(
            CompanyAgreementRuleExtractorV2.Category.SUNDAY,
            "Les heures du dimanche sont majorées de 50 %.",
            "ACCOTEXT1"
        )
        val two = companyRule(
            CompanyAgreementRuleExtractorV2.Category.SUNDAY,
            "Les heures du dimanche sont majorées de 100 %.",
            "ACCOTEXT2"
        )
        val selection = CollectivePremiumLegalArbitrationBridgeV2.resolveWeekday(
            company = companySnapshot(listOf(one, two)),
            branch = null,
            kind = WeekdayPremiumKindV2.SUNDAY,
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.State.CONFLICT, selection.resolution.state)
        assertNull(selection.selectedRule)
    }

    @Test
    fun `KALI peut devenir repli seulement avec absence ACCO confirmee`() {
        val selection = CollectivePremiumLegalArbitrationBridgeV2.resolveWeekday(
            company = companySnapshot(),
            branch = saturdayKali(),
            kind = WeekdayPremiumKindV2.SATURDAY,
            referenceDate = date,
            sourceKnowledge = mapOf(
                PayrollLegalArbitratorV2.Source.ACCO to PayrollLegalArbitratorV2.Knowledge.CONFIRMED_ABSENCE
            )
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, selection.resolution.state)
        assertEquals(PayrollLegalArbitratorV2.Source.KALI, selection.resolution.selected!!.source)
        assertEquals(1.25, selection.selectedRule!!.multiplier, 0.0001)
    }

    @Test
    fun `nuit KALI seule est aussi bloquee par ACCO inconnu`() {
        val branch = ConventionNightRuleSnapshotV2(
            idcc = "0292",
            versionId = "KALI-NIGHT-X",
            sourceId = "legifrance:KALI:KALIARTINIGHT",
            effectiveFromEpochDay = LocalDate.of(2026, 1, 1).toEpochDay(),
            effectiveToEpochDay = null,
            rule = NightPremiumRuleV2(21 * 60, 6 * 60, 1.25),
            checkedAtMs = 1L
        )
        val selection = CollectivePremiumLegalArbitrationBridgeV2.resolveNight(
            company = companySnapshot(),
            branch = branch,
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, selection.resolution.state)
        assertNull(selection.selectedRule)
    }

    @Test
    fun `jour ferie KALI seul reste bloque tant que ACCO est inconnu`() {
        val selection = CollectivePremiumLegalArbitrationBridgeV2.resolvePublicHoliday(
            company = companySnapshot(),
            branch = holidayKali(),
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.State.REVIEW_REQUIRED, selection.resolution.state)
        assertNull(selection.selectedRule)
    }

    @Test
    fun `accord entreprise jour ferie valide prime sur KALI`() {
        val acco = companyRule(
            CompanyAgreementRuleExtractorV2.Category.PUBLIC_HOLIDAY,
            "Les heures travaillées les jours fériés donnent lieu à une majoration de 75 %."
        )
        val selection = CollectivePremiumLegalArbitrationBridgeV2.resolvePublicHoliday(
            company = companySnapshot(listOf(acco)),
            branch = holidayKali(1.5),
            referenceDate = date
        )

        assertEquals(PayrollLegalArbitratorV2.State.RESOLVED, selection.resolution.state)
        assertEquals(PayrollLegalArbitratorV2.Source.ACCO, selection.resolution.selected!!.source)
        assertEquals(1.75, selection.selectedRule!!.multiplier, 0.0001)
    }
}
