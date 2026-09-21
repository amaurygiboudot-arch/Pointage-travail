package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ForfaitHoursPeriodV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmploymentContractFormInputV2Test {
    @Test
    fun hourlyContractKeepsHireAndEffectiveDatesDistinct() {
        val result = EmploymentContractFormInputBuilderV2.build(
            form = EmploymentContractFormInputV2(
                employerId = "company-1",
                contractType = "FULL_TIME",
                weeklyHours = "35",
                forfaitAnnualHours = "",
                forfaitAnnualDays = "",
                monthlyGrossSalary = "",
                grossHourlyRate = "13,70",
                hireDate = "01/02/2024",
                effectiveDate = "01/09/2026",
                sourceId = "Avenant signé"
            ),
            checkedAtMs = 123L
        )

        assertTrue(result.ready)
        assertNotNull(result.input)
        val input = result.input!!
        assertEquals("contract_company-1", input.contract.id)
        assertEquals(ContractTypeV2.FULL_TIME, input.contract.type)
        assertEquals(35 * 60, input.contract.contractualWeeklyMinutes)
        assertEquals(13.70, input.contract.grossHourlyRate!!, 0.0001)
        assertTrue(input.effectiveFromEpochDay > input.contract.hireDateEpochDay!!)
        assertEquals("Avenant signé", input.sourceId)
    }

    @Test
    fun effectiveDateBeforeHireDateIsRejected() {
        val result = EmploymentContractFormInputBuilderV2.build(
            form = EmploymentContractFormInputV2(
                employerId = "company-1",
                contractType = "PART_TIME",
                weeklyHours = "28",
                forfaitAnnualHours = "",
                forfaitAnnualDays = "",
                monthlyGrossSalary = "",
                grossHourlyRate = "14",
                hireDate = "01/09/2026",
                effectiveDate = "01/08/2026",
                sourceId = "Contrat signé"
            ),
            checkedAtMs = 123L
        )

        assertFalse(result.ready)
        assertTrue(result.warnings.any { it.contains("précéder") })
    }

    @Test
    fun annualHoursForfaitIsExplicitlyAnnual() {
        val result = EmploymentContractFormInputBuilderV2.build(
            form = EmploymentContractFormInputV2(
                employerId = "company-2",
                contractType = "FORFAIT_HEURES",
                weeklyHours = "",
                forfaitAnnualHours = "1607",
                forfaitAnnualDays = "",
                monthlyGrossSalary = "3200",
                grossHourlyRate = "",
                hireDate = "15/03/2020",
                effectiveDate = "01/01/2026",
                sourceId = "Convention individuelle"
            ),
            checkedAtMs = 456L
        )

        assertTrue(result.ready)
        assertNotNull(result.input)
        val contract = result.input!!.contract
        assertEquals(ContractTypeV2.FORFAIT_HOURS, contract.type)
        assertEquals(ForfaitHoursPeriodV2.YEAR, contract.forfaitHoursPeriod)
        assertEquals(1607.0, contract.forfaitHours!!, 0.0001)
        assertEquals(3200.0, contract.monthlyGrossSalary!!, 0.0001)
    }

    @Test
    fun sourceIsNeverInvented() {
        val result = EmploymentContractFormInputBuilderV2.build(
            form = EmploymentContractFormInputV2(
                employerId = "company-1",
                contractType = "FULL_TIME",
                weeklyHours = "35",
                forfaitAnnualHours = "",
                forfaitAnnualDays = "",
                monthlyGrossSalary = "",
                grossHourlyRate = "13.7",
                hireDate = "01/02/2024",
                effectiveDate = "01/09/2026",
                sourceId = "   "
            ),
            checkedAtMs = 123L
        )

        assertFalse(result.ready)
        assertEquals(listOf(EmploymentContractFormInputBuilderV2.INVALID_SOURCE_WARNING), result.warnings)
    }
}
