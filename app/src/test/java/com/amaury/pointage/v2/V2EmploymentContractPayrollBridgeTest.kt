package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolverV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2EmploymentContractPayrollBridgeTest {
    @Test
    fun `une version couvrant tout le mois est retenue`() {
        val januaryStart = LocalDate.of(2026, 1, 1).toEpochDay()
        val stored = reliable(listOf(snapshot("v1", januaryStart - 50, null, 14.0)))

        val result = V2EmploymentContractPayrollBridge.resolveStored(
            stored = stored,
            companyId = "company-a",
            year = 2026,
            monthZeroBased = 0
        )

        assertTrue(result.resolution.readyForSingleContractCalculation)
        assertEquals(14.0, result.resolution.contract!!.grossHourlyRate!!, 0.0)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `deux versions dans le mois bloquent le calcul unique`() {
        val first = LocalDate.of(2026, 1, 1).toEpochDay()
        val change = LocalDate.of(2026, 1, 16).toEpochDay()
        val stored = reliable(
            listOf(
                snapshot("v1", first - 20, change - 1, 13.5),
                snapshot("v2", change, null, 14.0)
            )
        )

        val result = V2EmploymentContractPayrollBridge.resolveStored(
            stored = stored,
            companyId = "company-a",
            year = 2026,
            monthZeroBased = 0
        )

        assertFalse(result.resolution.readyForSingleContractCalculation)
        assertTrue(result.resolution.requiresMultipleContractVersions)
        assertNull(result.resolution.contract)
        assertTrue(result.warnings.contains(EmploymentContractPeriodResolverV2.MULTIPLE_WARNING))
    }

    @Test
    fun `historique non fiable bloque meme si snapshot present`() {
        val first = LocalDate.of(2026, 1, 1).toEpochDay()
        val stored = V2EmploymentContractHistoryStore.ReadResult(
            snapshots = listOf(snapshot("v1", first - 20, null, 14.0)),
            reliable = false,
            repairedFromBackup = false,
            warnings = listOf(V2EmploymentContractHistoryStore.STORAGE_WARNING)
        )

        val result = V2EmploymentContractPayrollBridge.resolveStored(
            stored = stored,
            companyId = "company-a",
            year = 2026,
            monthZeroBased = 0
        )

        assertFalse(result.resolution.readyForSingleContractCalculation)
        assertNull(result.resolution.contract)
        assertTrue(result.warnings.contains(EmploymentContractPeriodResolverV2.UNRELIABLE_WARNING))
    }

    private fun reliable(snapshots: List<EmploymentContractSnapshotV2>) =
        V2EmploymentContractHistoryStore.ReadResult(
            snapshots = snapshots,
            reliable = true,
            repairedFromBackup = false,
            warnings = emptyList()
        )

    private fun snapshot(
        version: String,
        from: Long,
        to: Long?,
        rate: Double
    ) = EmploymentContractSnapshotV2(
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
