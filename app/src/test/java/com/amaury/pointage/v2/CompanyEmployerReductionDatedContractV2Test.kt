package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmploymentContractPeriodResolverV2
import com.amaury.pointage.v2.engine.EmploymentContractSnapshotV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompanyEmployerReductionDatedContractV2Test {
    private fun version(id: String, start: Long, end: Long?, type: ContractTypeV2, minutes: Int) =
        EmploymentContractSnapshotV2(
            versionId = id, sourceId = "contrat signé", effectiveFromEpochDay = start,
            effectiveToEpochDay = end, checkedAtMs = 1L,
            contract = ContractV2(id, "company-a", type, minutes, 15.0, 0L)
        )

    private val historical = version("ancien", 0, 129, ContractTypeV2.PART_TIME, 1200)
    private val current = version("actuel", 130, null, ContractTypeV2.FULL_TIME, 2100)

    private fun resolve(versions: List<EmploymentContractSnapshotV2>, reliable: Boolean = true) =
        CompanyEmployerReductionStoreV2.automaticContract(
            EmploymentContractPeriodResolverV2.resolve("company-a", 100, 129, reliable, versions)
        )

    @Test fun historicalMonthKeepsPartTimeBeforeFullTimeChange() {
        val contract = resolve(listOf(historical, current))!!
        assertEquals(ContractTypeV2.PART_TIME, contract.type)
        assertEquals(1200, contract.contractualWeeklyMinutes)
    }

    @Test fun currentContractDoesNotFillMissingHistoricalCoverage() {
        assertNull(resolve(listOf(current)))
    }

    @Test fun unreliableHistoryNeverSuppliesAutomaticContract() {
        assertNull(resolve(listOf(historical, current), reliable = false))
    }

    @Test fun materialMidMonthChangeRequiresSegmentedReduction() {
        val first = version("ancien", 0, 114, ContractTypeV2.PART_TIME, 1200)
        val second = version("actuel", 115, null, ContractTypeV2.FULL_TIME, 2100)
        assertNull(resolve(listOf(first, second)))
    }
}
