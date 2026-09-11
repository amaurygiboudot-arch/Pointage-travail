package com.amaury.pointage.v2

import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.v2.engine.CompanyBenefitInKindResolverV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionAnnualContextV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionContextV2
import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyEmployerGeneralReductionAnnualPayrollBridgeV2Test {
    private fun salary(
        gross: Double = 2_000.0,
        grossReliable: Boolean = true,
        tiers: List<V2SalaryAdapter.TierDuration> = emptyList(),
        warnings: List<String> = emptyList()
    ) = V2SalaryAdapter.Result(
        regularMs = 0L,
        overtimeTiers = tiers,
        totalWorkedMs = 0L,
        regularGross = gross,
        overtimeGross = 0.0,
        premiumsGross = 0.0,
        monthlyEstimatedGross = gross,
        monthlyGrossReliable = grossReliable,
        nightMs = 0L,
        saturdayMs = 0L,
        sundayMs = 0L,
        complementaryMinutes = 0,
        completedSessions = 1,
        warnings = warnings
    )

    private fun benefits(
        total: Double = 0.0,
        reliable: Boolean = true,
        warnings: List<String> = emptyList()
    ) = CompanyBenefitInKindResolverV2.Snapshot(
        applied = emptyList(),
        totalGross = total,
        reliable = reliable,
        warnings = warnings
    )

    private fun workforce(
        band: EmployerWorkforceContributionsV2.Band? = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
        source: String? = "DSN effectif 2026",
        reliable: Boolean = true,
        warnings: List<String> = emptyList()
    ) = EmployerWorkforceContributionsV2.Snapshot(
        band = band,
        source = source,
        reliable = reliable,
        warnings = warnings
    )

    private fun monthlyContext(
        fullMonth: Boolean? = true,
        standard: Boolean? = true,
        paidHoursComplete: Boolean? = true,
        source: String? = "Bulletin 01/2026",
        reliable: Boolean = true,
        warnings: List<String> = emptyList()
    ) = EmployerGeneralReductionContextV2.Snapshot(
        fullMonthPresent = fullMonth,
        standardCommonLawCaseConfirmed = standard,
        noOtherEmployerReductionConfirmed = false,
        source = source,
        reliable = reliable,
        warnings = warnings,
        paidHoursComplete = paidHoursComplete
    )

    private fun annualContext(
        band: EmployerWorkforceContributionsV2.Band? = EmployerWorkforceContributionsV2.Band.AT_LEAST_50,
        type: ContractTypeV2? = ContractTypeV2.FULL_TIME,
        weeklyMinutes: Int? = 35 * 60
    ) = EmployerGeneralReductionAnnualContextV2.Snapshot(
        fullCalendarYearPresent = true,
        standardCommonLawCaseConfirmed = true,
        homogeneousAnnualParametersConfirmed = true,
        source = "DSN annuelle 2026",
        reliable = true,
        warnings = emptyList(),
        confirmedWorkforceBand = band,
        confirmedContractType = type,
        confirmedContractualWeeklyMinutes = weeklyMinutes
    )

    @Test
    fun `reliable month uses confirmed historical annual contract parameters`() {
        val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.buildMonth(
            period = YearMonth.of(2026, 1),
            salary = salary(),
            benefits = benefits(125.50),
            workforce = workforce(),
            monthlyContext = monthlyContext(),
            annualContext = annualContext(type = ContractTypeV2.FULL_TIME, weeklyMinutes = 35 * 60)
        )

        assertTrue(result.reliable)
        assertEquals(ContractTypeV2.FULL_TIME, result.contractType)
        assertEquals(35 * 60, result.contractualWeeklyMinutes)
        assertEquals(2_125.50, result.reductionRemunerationMonthly!!, 0.000001)
        assertNotNull(result.automaticRgduAdvanceAmount)
        assertTrue(result.source!!.contains("Bulletin 01/2026"))
        assertTrue(result.source!!.contains("DSN effectif 2026"))
    }

    @Test
    fun `fractional additional paid minute survives monthly reconstruction`() {
        val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.buildMonth(
            period = YearMonth.of(2026, 2),
            salary = salary(
                tiers = listOf(V2SalaryAdapter.TierDuration("25 %", 90_030L, 1.25))
            ),
            benefits = benefits(),
            workforce = workforce(),
            monthlyContext = monthlyContext(),
            annualContext = annualContext()
        )

        assertTrue(result.reliable)
        assertEquals(1.5005, result.additionalPaidMinutes!!, 0.000000001)
        assertNotNull(result.automaticRgduAdvanceAmount)
    }

    @Test
    fun `unknown paid hours completeness blocks reconstructed advance`() {
        val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.buildMonth(
            period = YearMonth.of(2026, 3),
            salary = salary(),
            benefits = benefits(),
            workforce = workforce(),
            monthlyContext = monthlyContext(paidHoursComplete = null),
            annualContext = annualContext()
        )

        assertFalse(result.reliable)
        assertNull(result.additionalPaidMinutes)
        assertNull(result.automaticRgduAdvanceAmount)
        assertTrue(result.warnings.any { it.contains("exhaustivité", ignoreCase = true) })
    }

    @Test
    fun `unreliable benefits in kind never become implicit zero`() {
        val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.buildMonth(
            period = YearMonth.of(2026, 4),
            salary = salary(),
            benefits = benefits(
                total = 0.0,
                reliable = false,
                warnings = listOf("avantage en nature à confirmer")
            ),
            workforce = workforce(),
            monthlyContext = monthlyContext(),
            annualContext = annualContext()
        )

        assertFalse(result.reliable)
        assertNull(result.reductionRemunerationMonthly)
        assertNull(result.automaticRgduAdvanceAmount)
        assertTrue(result.warnings.any { it.contains("avantage en nature", ignoreCase = true) })
    }

    @Test
    fun `missing historical annual contract type blocks month even when salary is available`() {
        val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.buildMonth(
            period = YearMonth.of(2026, 5),
            salary = salary(),
            benefits = benefits(),
            workforce = workforce(),
            monthlyContext = monthlyContext(),
            annualContext = annualContext(type = null)
        )

        assertFalse(result.reliable)
        assertNull(result.contractType)
        assertNull(result.automaticRgduAdvanceAmount)
        assertTrue(result.warnings.any { it.contains("type de contrat historique", ignoreCase = true) })
    }

    @Test
    fun `missing historical annual weekly duration blocks month`() {
        val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.buildMonth(
            period = YearMonth.of(2026, 6),
            salary = salary(),
            benefits = benefits(),
            workforce = workforce(),
            monthlyContext = monthlyContext(),
            annualContext = annualContext(weeklyMinutes = null)
        )

        assertFalse(result.reliable)
        assertNull(result.contractualWeeklyMinutes)
        assertNull(result.automaticRgduAdvanceAmount)
        assertTrue(result.warnings.any { it.contains("durée contractuelle historique", ignoreCase = true) })
    }

    @Test
    fun `workforce failure remains visible and blocks automatic advance`() {
        val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.buildMonth(
            period = YearMonth.of(2026, 7),
            salary = salary(),
            benefits = benefits(),
            workforce = workforce(
                band = null,
                reliable = false,
                warnings = listOf("effectif mensuel à confirmer")
            ),
            monthlyContext = monthlyContext(),
            annualContext = annualContext()
        )

        assertFalse(result.reliable)
        assertNull(result.automaticRgduAdvanceAmount)
        assertTrue(result.warnings.any { it.contains("effectif mensuel", ignoreCase = true) })
    }

    @Test
    fun `monthly provenance is mandatory`() {
        val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.buildMonth(
            period = YearMonth.of(2026, 8),
            salary = salary(),
            benefits = benefits(),
            workforce = workforce(source = null),
            monthlyContext = monthlyContext(source = null),
            annualContext = annualContext()
        )

        assertFalse(result.reliable)
        assertNull(result.source)
        assertTrue(result.warnings.any { it.contains("provenance mensuelle", ignoreCase = true) })
    }

    @Test
    fun `monthly context false remains a blocker and is never promoted to true`() {
        val result = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.buildMonth(
            period = YearMonth.of(2026, 9),
            salary = salary(),
            benefits = benefits(),
            workforce = workforce(),
            monthlyContext = monthlyContext(fullMonth = false),
            annualContext = annualContext()
        )

        assertFalse(result.reliable)
        assertFalse(result.fullMonthPresent!!)
        assertNull(result.automaticRgduAdvanceAmount)
        assertTrue(result.warnings.any { it.contains("mois incomplet", ignoreCase = true) })
    }

    @Test
    fun `annual RGDU blocks an unreliable company store before reading annual context`() {
        val company = SalaryCompanyStore.Company(
            id = "company-a",
            name = "Entreprise A",
            siret = "12345678901234"
        )
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = false,
            warnings = listOf("store entreprises corrompu")
        )

        val blockers = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.companyStoreBlockers(stored, company.id)

        assertTrue(blockers.any { it.contains("corrompu", ignoreCase = true) })
    }

    @Test
    fun `annual RGDU never reads orphan company preferences`() {
        val stored = SalaryCompanyStore.ReadResult(emptyList(), reliable = true)

        val blockers = CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.companyStoreBlockers(stored, "company-a")

        assertTrue(blockers.any { it.contains("orpheline", ignoreCase = true) })
    }

    @Test
    fun `annual RGDU accepts a company restored from last known good`() {
        val company = SalaryCompanyStore.Company(
            id = "company-a",
            name = "Entreprise A",
            siret = "12345678901234"
        )
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = true,
            repairedFromBackup = true,
            warnings = listOf("restauré depuis la copie saine")
        )

        assertTrue(
            CompanyEmployerGeneralReductionAnnualPayrollBridgeV2.companyStoreBlockers(
                stored,
                " company-a "
            ).isEmpty()
        )
    }
}
