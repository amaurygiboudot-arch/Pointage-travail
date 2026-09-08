package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NetSalaryProvidentKaliRefreshV2Test {
    private val date = LocalDate.of(2026, 1, 31)
    private val classification = ConventionClassificationV2(coefficient = 700)

    private fun company(acquiredKali: Boolean): CompanyPayrollOverridesV2.Snapshot {
        val record = ConventionMatterCoverageV2.Record(
            idcc = "292",
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
            effectiveFrom = LocalDate.of(2026, 1, 1),
            effectiveTo = LocalDate.of(2026, 1, 31),
            classification = classification,
            professionalStatus = "NON_CADRE",
            state = ConventionMatterCoverageV2.State.INCOMPLETE,
            source = "refresh KALI incomplet",
            checkedAtMs = 2L,
            authorities = emptySet(),
            acquiredAuthorities = if (acquiredKali) {
                setOf(ConventionMatterCoverageV2.Authority.KALI)
            } else emptySet()
        )
        return CompanyPayrollOverridesV2.Snapshot(
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
            protectionCategory = PlasturgieProtectionCategoryV2.classify("292", date, 700),
            warnings = emptyList(),
            verifiedProtectionCategory = ProtectionCategoryV2.Result(
                aniCategory = ProtectionCategoryV2.AniCategory.OUTSIDE_2_1_2_2,
                confirmed = true,
                source = "test KALI + APEC"
            ),
            verifiedProvidentClassification = classification,
            verifiedProvidentSeniorityMonths = 72,
            verifiedProvidentRules = emptyList(),
            verifiedProvidentCoverage = ConventionMatterCoverageV2.Snapshot(
                state = ConventionMatterCoverageV2.State.INCOMPLETE,
                record = record,
                reliable = false,
                warnings = listOf("refresh KALI incomplet")
            )
        )
    }

    @Test
    fun `avant toute acquisition KALI le fallback Plasturgie reste disponible pendant la migration`() {
        val result = NetSalaryEngineV2.calculate(2500.0, 2026, company(acquiredKali = false))

        assertEquals(10.0, result.conventionProvidentEmployee, 0.001)
        assertEquals(10.0, result.conventionProvidentEmployer, 0.001)
    }

    @Test
    fun `apres acquisition KALI un refresh incomplet ne reactive jamais Plasturgie`() {
        val result = NetSalaryEngineV2.calculate(2500.0, 2026, company(acquiredKali = true))

        assertEquals(0.0, result.conventionProvidentEmployee, 0.001)
        assertEquals(0.0, result.conventionProvidentEmployer, 0.001)
        assertTrue(result.warnings.any {
            it.contains("chemin KALI déjà acquis", ignoreCase = true) &&
                it.contains("aucun ancien barème", ignoreCase = true)
        })
    }
}
