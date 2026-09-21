package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractSegmentPayrollCompatibilityV2Test {
    @Test
    fun `deux versions identiques peuvent garder un calcul mensuel unique`() {
        val result = ContractSegmentPayrollCompatibilityV2.resolve(
            listOf(segment("v1", 1, 14, contract(rate = 13.7)), segment("v2", 15, 30, contract(rate = 13.7)))
        )

        assertTrue(result.compatibleForSingleMonthlyCalculation)
        assertNotNull(result.contract)
        assertTrue(result.changedFields.isEmpty())
        assertEquals(listOf(ContractSegmentPayrollCompatibilityV2.EQUIVALENT_VERSIONS_WARNING), result.warnings)
    }

    @Test
    fun `changement de taux bloque la proratisation implicite`() {
        val result = ContractSegmentPayrollCompatibilityV2.resolve(
            listOf(segment("v1", 1, 14, contract(rate = 13.7)), segment("v2", 15, 30, contract(rate = 14.2)))
        )

        assertFalse(result.compatibleForSingleMonthlyCalculation)
        assertTrue(result.changedFields.contains("grossHourlyRate"))
        assertEquals(listOf(ContractSegmentPayrollCompatibilityV2.CHANGED_PAYROLL_INPUTS_WARNING), result.warnings)
    }

    @Test
    fun `changement de duree contractuelle bloque aussi`() {
        val result = ContractSegmentPayrollCompatibilityV2.resolve(
            listOf(segment("v1", 1, 14, contract(rate = 13.7, weeklyMinutes = 35 * 60)), segment("v2", 15, 30, contract(rate = 13.7, weeklyMinutes = 39 * 60)))
        )

        assertFalse(result.compatibleForSingleMonthlyCalculation)
        assertTrue(result.changedFields.contains("contractualWeeklyMinutes"))
    }

    private fun contract(rate: Double, weeklyMinutes: Int = 35 * 60) = ContractV2(
        id = "contract-company",
        employerId = "company",
        type = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = weeklyMinutes,
        grossHourlyRate = rate,
        hireDateEpochDay = 1L
    )

    private fun segment(version: String, start: Long, end: Long, contract: ContractV2) =
        EmploymentContractCoverageSegmentV2(
            startEpochDay = start,
            endEpochDay = end,
            snapshot = EmploymentContractSnapshotV2(
                versionId = version,
                sourceId = "source-$version",
                effectiveFromEpochDay = start,
                effectiveToEpochDay = end,
                contract = contract,
                checkedAtMs = 1L
            )
        )
}
