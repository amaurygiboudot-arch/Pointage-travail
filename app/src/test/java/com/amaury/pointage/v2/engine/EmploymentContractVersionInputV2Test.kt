package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmploymentContractVersionInputV2Test {
    @Test
    fun `date effet distincte de date embauche est acceptee`() {
        val input = EmploymentContractVersionInputV2(
            contract = hourlyContract(hireDate = 100),
            effectiveFromEpochDay = 250,
            sourceId = "avenant-confirmed",
            checkedAtMs = 1
        )

        val result = EmploymentContractVersionInputValidatorV2.validate(input)

        assertTrue(result.ready)
        assertTrue(result.warnings.isEmpty())
        assertEquals(100L, input.contract.hireDateEpochDay)
        assertEquals(250L, input.effectiveFromEpochDay)
    }

    @Test
    fun `date effet avant entree est refusee sans la corriger`() {
        val input = EmploymentContractVersionInputV2(
            contract = hourlyContract(hireDate = 100),
            effectiveFromEpochDay = 99,
            sourceId = "user-confirmed",
            checkedAtMs = 1
        )

        val result = EmploymentContractVersionInputValidatorV2.validate(input)

        assertFalse(result.ready)
        assertEquals(
            listOf(EmploymentContractVersionInputValidatorV2.EFFECT_BEFORE_HIRE_WARNING),
            result.warnings
        )
        assertEquals(99L, input.effectiveFromEpochDay)
    }

    @Test
    fun `absence date embauche ne fabrique aucune date`() {
        val input = EmploymentContractVersionInputV2(
            contract = hourlyContract(hireDate = null),
            effectiveFromEpochDay = 500,
            sourceId = "user-confirmed",
            checkedAtMs = 1
        )

        val result = EmploymentContractVersionInputValidatorV2.validate(input)

        assertTrue(result.ready)
        assertEquals(null, input.contract.hireDateEpochDay)
        assertEquals(500L, input.effectiveFromEpochDay)
    }

    @Test
    fun `ancien forfait generique est refuse`() {
        val contract = hourlyContract(hireDate = 100).copy(type = ContractTypeV2.FORFAIT)
        val result = EmploymentContractVersionInputValidatorV2.validate(
            EmploymentContractVersionInputV2(
                contract = contract,
                effectiveFromEpochDay = 100,
                sourceId = "user-confirmed",
                checkedAtMs = 1
            )
        )

        assertFalse(result.ready)
        assertTrue(result.warnings.contains(EmploymentContractVersionInputValidatorV2.LEGACY_FORFAIT_WARNING))
    }

    @Test
    fun `forfait jours au dela de 218 est refuse comme contenu contractuel`() {
        val contract = ContractV2(
            id = "contract-a",
            employerId = "company-a",
            type = ContractTypeV2.FORFAIT_DAYS,
            contractualWeeklyMinutes = null,
            grossHourlyRate = null,
            hireDateEpochDay = 100,
            forfaitHoursPeriod = null,
            forfaitHours = null,
            forfaitAnnualDays = 219.0,
            monthlyGrossSalary = 3_000.0
        )
        val result = EmploymentContractVersionInputValidatorV2.validate(
            EmploymentContractVersionInputV2(
                contract = contract,
                effectiveFromEpochDay = 100,
                sourceId = "user-confirmed",
                checkedAtMs = 1
            )
        )

        assertFalse(result.ready)
        assertTrue(result.warnings.contains(EmploymentContractVersionInputValidatorV2.INVALID_TERMS_WARNING))
    }

    private fun hourlyContract(hireDate: Long?) = ContractV2(
        id = "contract-a",
        employerId = "company-a",
        type = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = 35 * 60,
        grossHourlyRate = 14.0,
        hireDateEpochDay = hireDate
    )
}
