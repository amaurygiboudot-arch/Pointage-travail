package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertTrue
import org.junit.Test

class EmploymentContractHistoryForfaitDaysBoundaryV2Test {
    @Test
    fun `218 jours est historisable mais 219 est refuse`() {
        assertTrue(runCatching { snapshot(days = 218.0) }.isSuccess)
        assertTrue(runCatching { snapshot(days = 219.0) }.isFailure)
    }

    private fun snapshot(days: Double) = EmploymentContractSnapshotV2(
        versionId = "days-$days",
        sourceId = "user-confirmed",
        effectiveFromEpochDay = 100,
        effectiveToEpochDay = null,
        contract = ContractV2(
            id = "contract-days-$days",
            employerId = "company-a",
            type = ContractTypeV2.FORFAIT_DAYS,
            contractualWeeklyMinutes = null,
            grossHourlyRate = null,
            hireDateEpochDay = null,
            forfaitAnnualDays = days,
            monthlyGrossSalary = 4200.0
        ),
        checkedAtMs = 1L
    )
}
