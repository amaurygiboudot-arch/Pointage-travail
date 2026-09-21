package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmploymentContractTimelineV2Test {
    @Test
    fun `premiere version commence au jour confirme et reste ouverte`() {
        val updated = EmploymentContractTimelineV2.upsertEffectiveVersion(
            existing = emptyList(),
            contract = contract("company-a", 13.5),
            effectiveFromEpochDay = 100,
            sourceId = "user-confirmed",
            checkedAtMs = 1
        )!!

        assertEquals(1, updated.size)
        assertEquals(100, updated.single().effectiveFromEpochDay)
        assertNull(updated.single().effectiveToEpochDay)
        assertEquals("effective-100", updated.single().versionId)
    }

    @Test
    fun `nouvelle version ferme la version ouverte la veille`() {
        val first = snapshot("v1", "company-a", 100, null, 13.5)

        val updated = EmploymentContractTimelineV2.upsertEffectiveVersion(
            existing = listOf(first),
            contract = contract("company-a", 14.0),
            effectiveFromEpochDay = 200,
            sourceId = "avenant",
            checkedAtMs = 2
        )!!

        val history = EmploymentContractHistoryV2(updated)
        assertEquals(199L, history.allVersions("company-a").first { it.versionId == "v1" }.effectiveToEpochDay)
        assertEquals(14.0, history.applicable("company-a", 200)!!.contract.grossHourlyRate!!, 0.0)
    }

    @Test
    fun `insertion au milieu se termine avant la prochaine version`() {
        val first = snapshot("v1", "company-a", 100, 300, 13.5)
        val third = snapshot("v3", "company-a", 400, null, 15.0)

        val updated = EmploymentContractTimelineV2.upsertEffectiveVersion(
            existing = listOf(first, third),
            contract = contract("company-a", 14.0),
            effectiveFromEpochDay = 250,
            sourceId = "avenant",
            checkedAtMs = 2
        )!!

        val history = EmploymentContractHistoryV2(updated)
        assertEquals(249L, history.allVersions("company-a").first { it.versionId == "v1" }.effectiveToEpochDay)
        val inserted = history.applicable("company-a", 300)!!
        assertEquals(250, inserted.effectiveFromEpochDay)
        assertEquals(399L, inserted.effectiveToEpochDay)
        assertEquals("v3", history.applicable("company-a", 400)?.versionId)
    }

    @Test
    fun `un trou historique avant la date confirmee reste un trou`() {
        val old = snapshot("old", "company-a", 10, 20, 12.0)
        val future = snapshot("future", "company-a", 100, null, 15.0)

        val updated = EmploymentContractTimelineV2.upsertEffectiveVersion(
            existing = listOf(old, future),
            contract = contract("company-a", 14.0),
            effectiveFromEpochDay = 50,
            sourceId = "user-confirmed",
            checkedAtMs = 3
        )!!

        val history = EmploymentContractHistoryV2(updated)
        assertNull(history.applicable("company-a", 30))
        assertEquals("old", history.applicable("company-a", 20)?.versionId)
        assertEquals(14.0, history.applicable("company-a", 50)!!.contract.grossHourlyRate!!, 0.0)
        assertEquals(99L, history.applicable("company-a", 50)?.effectiveToEpochDay)
    }

    @Test
    fun `modifier la meme date conserve identifiant et borne de fin`() {
        val old = snapshot("stable-id", "company-a", 100, 199, 13.5)
        val next = snapshot("next", "company-a", 200, null, 15.0)

        val updated = EmploymentContractTimelineV2.upsertEffectiveVersion(
            existing = listOf(old, next),
            contract = contract("company-a", 14.25),
            effectiveFromEpochDay = 100,
            sourceId = "correction-user",
            checkedAtMs = 4
        )!!

        val history = EmploymentContractHistoryV2(updated)
        val corrected = history.applicable("company-a", 150)!!
        assertEquals("stable-id", corrected.versionId)
        assertEquals(199L, corrected.effectiveToEpochDay)
        assertEquals(14.25, corrected.contract.grossHourlyRate!!, 0.0)
    }

    @Test
    fun `autre employeur est strictement preserve`() {
        val companyB = snapshot("b1", "company-b", 1, null, 20.0)

        val updated = EmploymentContractTimelineV2.upsertEffectiveVersion(
            existing = listOf(companyB),
            contract = contract("company-a", 13.5),
            effectiveFromEpochDay = 100,
            sourceId = "user-confirmed",
            checkedAtMs = 5
        )!!

        assertTrue(updated.contains(companyB))
        assertEquals(20.0, EmploymentContractHistoryV2(updated).applicable("company-b", 500)!!.contract.grossHourlyRate!!, 0.0)
    }

    @Test
    fun `historique incoherent est refuse au lieu detre repare implicitement`() {
        val overlapping = listOf(
            snapshot("v1", "company-a", 1, 100, 13.0),
            snapshot("v2", "company-a", 50, null, 14.0)
        )

        val result = EmploymentContractTimelineV2.upsertEffectiveVersion(
            existing = overlapping,
            contract = contract("company-a", 15.0),
            effectiveFromEpochDay = 200,
            sourceId = "user-confirmed",
            checkedAtMs = 6
        )

        assertNull(result)
    }

    private fun contract(companyId: String, rate: Double) = ContractV2(
        id = "contract-$companyId",
        employerId = companyId,
        type = ContractTypeV2.FULL_TIME,
        contractualWeeklyMinutes = 35 * 60,
        grossHourlyRate = rate,
        hireDateEpochDay = null
    )

    private fun snapshot(
        version: String,
        companyId: String,
        from: Long,
        to: Long?,
        rate: Double
    ) = EmploymentContractSnapshotV2(
        versionId = version,
        sourceId = "test",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        contract = contract(companyId, rate),
        checkedAtMs = 1
    )
}
