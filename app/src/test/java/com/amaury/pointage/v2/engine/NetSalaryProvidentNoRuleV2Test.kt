package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NetSalaryProvidentNoRuleV2Test {
    private val date = LocalDate.of(2026, 1, 31)
    private val classification = ConventionClassificationV2(coefficient = 700)
    private val legacy = PlasturgieProtectionCategoryV2.classify("292", date, 700)

    private val coverageRecord = ConventionMatterCoverageV2.Record(
        idcc = "292",
        matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
        effectiveFrom = LocalDate.of(2026, 1, 1),
        effectiveTo = LocalDate.of(2026, 1, 31),
        classification = classification,
        professionalStatus = "NON_CADRE",
        state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
        source = "audit KALI no-rule test",
        checkedAtMs = 1L,
        authorities = setOf(ConventionMatterCoverageV2.Authority.KALI)
    )

    private fun company(categoryConfirmed: Boolean = true) = CompanyPayrollOverridesV2.Snapshot(
        companyId = "company",
        idcc = "292",
        referenceDate = date,
        entryDate = LocalDate.of(2020, 1, 1),
        seniorityMonths = 72,
        contractType = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = 35 * 60,
        forfaitAnnualDays = null,
        unpaidAbsenceDays = 0,
        hasUnpaidAbsence = false,
        mealAmount = 0.0,
        mutualEmployeeAmount = 0.0,
        providentEmployeeAmount = null,
        transportEmployeeAmount = 0.0,
        employerProtectionTaxableAmount = 0.0,
        employeeProvidentNonDeductibleAmount = 0.0,
        incomeTaxRate = 0.0,
        professionalStatus = "NON_CADRE",
        protectionCategory = legacy,
        warnings = emptyList(),
        alsaceMoselleLocalRegime = false,
        employerMobilityRate = 0.0,
        verifiedProtectionCategory = ProtectionCategoryV2.Result(
            aniCategory = if (categoryConfirmed) {
                ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2
            } else {
                ProtectionCategoryV2.AniCategory.TO_CONFIRM
            },
            confirmed = categoryConfirmed,
            source = if (categoryConfirmed) "test KALI + APEC" else null
        ),
        verifiedProvidentClassification = classification,
        verifiedProvidentSeniorityMonths = 72,
        verifiedProvidentRules = emptyList(),
        verifiedProvidentCoverage = ConventionMatterCoverageV2.Snapshot(
            state = ConventionMatterCoverageV2.State.CONFIRMED_NO_RULE,
            record = coverageRecord,
            reliable = true,
            warnings = emptyList()
        )
    )

    @Test
    fun `confirmed no rule KALI bloque le fallback Plasturgie`() {
        assertEquals(PlasturgieProtectionCategoryV2.Category.OUTSIDE_2_1_2_2, legacy.category)

        val result = NetSalaryEngineV2.calculate(2500.0, 2026, company())

        assertEquals(0.0, result.conventionProvidentEmployee, 0.001)
        assertEquals(0.0, result.conventionProvidentEmployer, 0.001)
        assertEquals(0.0, result.companyEmployeeDeductions, 0.001)
        assertTrue(result.warnings.any { it.contains("absence de cotisation", ignoreCase = true) })
    }

    @Test
    fun `ancien no rule KALI ne vaut pas zéro si catégorie ANI actuelle est inconnue`() {
        val result = NetSalaryEngineV2.calculate(2500.0, 2026, company(categoryConfirmed = false))

        // Les champs numériques restent à zéro dans le DTO historique, mais aucune cotisation
        // legacy n'est réintroduite et la complétude fiscale est explicitement bloquée.
        assertEquals(0.0, result.conventionProvidentEmployee, 0.001)
        assertEquals(0.0, result.conventionProvidentEmployer, 0.001)
        assertEquals(0.0, result.companyEmployeeDeductions, 0.001)
        assertNull(result.netTaxable)
        assertNull(result.incomeTax)
        assertTrue(result.warnings.any { it.contains("catégorie ANI actuelle non confirmée", ignoreCase = true) })
        assertTrue(result.warnings.any { it.contains("assiette fiscale incomplète", ignoreCase = true) })
    }
}
