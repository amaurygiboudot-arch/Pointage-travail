package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CompanyPayrollDatedContractCeilingV2Test {
    private val start = LocalDate.of(2026, 1, 1)
    private val end = LocalDate.of(2026, 1, 31)
    private val hire = LocalDate.of(2020, 1, 1).toEpochDay()
    private val current = ContractV2("actuel", "company-a", ContractTypeV2.FULL_TIME, 2100, 20.0, hire)

    private fun version(contract: ContractV2, from: LocalDate, to: LocalDate? = null) =
        EmploymentContractSnapshotV2(contract.id, "contrat signé", from.toEpochDay(), to?.toEpochDay(), contract, 1L)

    private fun resolve(versions: List<EmploymentContractSnapshotV2>, reliable: Boolean = true) =
        CompanyPayrollOverridesV2.contractForPeriod(
            EmploymentContractPeriodResolverV2.resolve("company-a", start.toEpochDay(), end.toEpochDay(), reliable, versions)
        )

    private fun ceiling(contract: ContractV2?) = SocialSecurityCeilingV2.calculate(
        SocialSecurityCeilingV2.Input(
            year = 2026, referenceDate = end, contractType = contract?.type,
            contractualWeeklyMinutes = contract?.contractualWeeklyMinutes,
            complementaryMinutes = 0,
            entryDate = contract?.hireDateEpochDay?.let(LocalDate::ofEpochDay),
            forfaitAnnualDays = contract?.forfaitAnnualDays
        )
    )

    @Test fun historicalPartTimeKeepsProratedCeilingAfterFullTimeChange() {
        val old = current.copy(id = "ancien", type = ContractTypeV2.PART_TIME, contractualWeeklyMinutes = 1200)
        val contract = resolve(listOf(version(old, start, end), version(current, end.plusDays(1))))
        val historical = ceiling(contract)
        assertTrue(historical.complete)
        assertEquals(SocialSecurityCeilingV2.fullMonthly(2026)!! * 20.0 / 35.0, historical.applicableMonthly, 0.001)
        assertTrue(historical.applicableMonthly < ceiling(current).applicableMonthly)
    }

    @Test fun historicalForfaitDaysKeepTheirAnnualDuration() {
        val old = ContractV2("forfait", "company-a", ContractTypeV2.FORFAIT_DAYS, null, null, hire,
            forfaitAnnualDays = 180.0, monthlyGrossSalary = 2500.0)
        val contract = resolve(listOf(version(old, start, end), version(current, end.plusDays(1))))
        assertEquals(180.0, contract?.forfaitAnnualDays ?: -1.0, 0.0)
        val historical = ceiling(contract)
        assertTrue(historical.complete)
        assertTrue(historical.applicableMonthly < ceiling(current).applicableMonthly)
    }

    @Test fun currentContractCannotSupplyMissingHistoricalCeiling() {
        val contract = resolve(listOf(version(current, end.plusDays(1))))
        assertNull(contract)
        assertFalse(ceiling(contract).complete)
    }

    @Test fun unreliableHistoryCannotSupplyCeilingContract() {
        assertNull(resolve(listOf(version(current, start)), reliable = false))
    }

    @Test fun materialMidMonthChangeDoesNotChooseLatestContract() {
        val old = current.copy(id = "ancien", type = ContractTypeV2.PART_TIME, contractualWeeklyMinutes = 1200)
        assertNull(resolve(listOf(version(old, start, start.plusDays(14)), version(current, start.plusDays(15)))))
    }
}
