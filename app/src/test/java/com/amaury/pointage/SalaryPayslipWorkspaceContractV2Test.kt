package com.amaury.pointage

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SalaryPayslipWorkspaceContractV2Test {
    @Test
    fun `la presentation vient du contrat date et de l entreprise V2`() {
        val company = SalaryCompanyStore.Company(
            id = "company-a",
            name = "Entreprise A",
            siret = "12345678901234",
            idcc = " 292 "
        )
        val contract = ContractV2(
            id = "contract-2026-09",
            employerId = company.id,
            type = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = 39 * 60,
            grossHourlyRate = 14.25,
            hireDateEpochDay = LocalDate.of(2020, 1, 15).toEpochDay()
        )

        val result = salaryPayslipWorkspaceContractPresentationV2(company, contract, 2026, 8)

        assertEquals("292", result.conventionId)
        assertEquals("39h00", result.weeklyLabel)
        assertEquals("6 ans et 8 mois", result.seniorityLabel)
    }

    @Test
    fun `un contrat mensuel non resolu ne reactive pas les valeurs courantes`() {
        val company = SalaryCompanyStore.Company(
            id = "company-a",
            name = "Entreprise A",
            siret = "12345678901234",
            idcc = ""
        )

        val result = salaryPayslipWorkspaceContractPresentationV2(company, null, 2026, 8)

        assertNull(result.conventionId)
        assertEquals("à confirmer", result.weeklyLabel)
        assertEquals("à confirmer", result.seniorityLabel)
    }
}
