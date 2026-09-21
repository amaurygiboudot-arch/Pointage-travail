package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmploymentContractPeriodResolverV2Test {
    @Test
    fun `une version couvrant tout le mois est utilisable`() {
        val result = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company-a",
            periodStartEpochDay = 100,
            periodEndEpochDay = 129,
            sourceReliable = true,
            snapshots = listOf(snapshot("v1", 50, null, 13.5))
        )

        assertTrue(result.readyForSingleContractCalculation)
        assertFalse(result.requiresMultipleContractVersions)
        assertEquals(13.5, result.contract!!.grossHourlyRate!!, 0.0)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `trou dans la periode bloque sans fallback`() {
        val result = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company-a",
            periodStartEpochDay = 100,
            periodEndEpochDay = 129,
            sourceReliable = true,
            snapshots = listOf(snapshot("v1", 100, 110, 13.5))
        )

        assertFalse(result.readyForSingleContractCalculation)
        assertNull(result.contract)
        assertEquals(listOf(EmploymentContractPeriodResolverV2.INCOMPLETE_WARNING), result.warnings)
    }

    @Test
    fun `deux versions dans la periode bloquent le contrat unique`() {
        val result = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company-a",
            periodStartEpochDay = 100,
            periodEndEpochDay = 129,
            sourceReliable = true,
            snapshots = listOf(
                snapshot("v1", 50, 114, 13.5),
                snapshot("v2", 115, null, 14.0)
            )
        )

        assertFalse(result.readyForSingleContractCalculation)
        assertTrue(result.requiresMultipleContractVersions)
        assertNull(result.contract)
        assertEquals(listOf(EmploymentContractPeriodResolverV2.MULTIPLE_WARNING), result.warnings)
    }

    @Test
    fun `source non fiable bloque meme avec un snapshot valide`() {
        val result = EmploymentContractPeriodResolverV2.resolve(
            employerId = "company-a",
            periodStartEpochDay = 100,
            periodEndEpochDay = 129,
            sourceReliable = false,
            snapshots = listOf(snapshot("v1", 50, null, 13.5))
        )

        assertFalse(result.sourceReliable)
        assertFalse(result.readyForSingleContractCalculation)
        assertNull(result.contract)
        assertEquals(listOf(EmploymentContractPeriodResolverV2.UNRELIABLE_WARNING), result.warnings)
    }

    private fun snapshot(version: String, from: Long, to: Long?, rate: Double) =
        EmploymentContractSnapshotV2(
            versionId = version,
            sourceId = "test",
            effectiveFromEpochDay = from,
            effectiveToEpochDay = to,
            contract = ContractV2(
                id = "contract-$version",
                employerId = "company-a",
                type = ContractTypeV2.FULL_TIME,
                contractualWeeklyMinutes = 35 * 60,
                grossHourlyRate = rate,
                hireDateEpochDay = null
            ),
            checkedAtMs = 1
        )
}
