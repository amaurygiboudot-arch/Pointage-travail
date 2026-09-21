package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmploymentContractHistoryV2Test {
    @Test
    fun `une version couvre toute la periode sans fallback`() {
        val history = EmploymentContractHistoryV2(listOf(snapshot("v1", 100, 199, 13.5)))
        val coverage = history.coverage("company-a", 120, 150)

        assertTrue(coverage.fullyCovered)
        assertEquals(1, coverage.segments.size)
        assertEquals(13.5, coverage.singleSnapshotForWholePeriod?.contract?.grossHourlyRate ?: 0.0, 0.0)
        assertFalse(coverage.requiresMultipleContractVersions)
    }

    @Test
    fun `deux versions contigues restent distinctes`() {
        val history = EmploymentContractHistoryV2(listOf(
            snapshot("v1", 100, 129, 13.5),
            snapshot("v2", 130, 199, 14.0)
        ))
        val coverage = history.coverage("company-a", 120, 150)

        assertTrue(coverage.fullyCovered)
        assertEquals(listOf("v1", "v2"), coverage.segments.map { it.snapshot.versionId })
        assertNull(coverage.singleSnapshotForWholePeriod)
        assertTrue(coverage.requiresMultipleContractVersions)
    }

    @Test
    fun `un trou ne reutilise aucune autre version`() {
        val history = EmploymentContractHistoryV2(listOf(
            snapshot("v1", 100, 129, 13.5),
            snapshot("v2", 131, 199, 14.0)
        ))
        val coverage = history.coverage("company-a", 120, 150)

        assertFalse(coverage.fullyCovered)
        assertNull(coverage.singleSnapshotForWholePeriod)
    }

    @Test
    fun `une version future nest jamais un fallback historique`() {
        val history = EmploymentContractHistoryV2(listOf(snapshot("future", 200, null, 15.0)))

        assertNull(history.applicable("company-a", 150))
        val coverage = history.coverage("company-a", 120, 150)
        assertFalse(coverage.fullyCovered)
        assertTrue(coverage.segments.isEmpty())
    }

    @Test
    fun `des versions qui se chevauchent sont refusees`() {
        val failure = runCatching {
            EmploymentContractHistoryV2(listOf(
                snapshot("v1", 100, 140, 13.5),
                snapshot("v2", 130, 199, 14.0)
            ))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `une version dupliquee pour le meme employeur est refusee`() {
        val failure = runCatching {
            EmploymentContractHistoryV2(listOf(
                snapshot("v1", 100, 129, 13.5),
                snapshot("v1", 130, 199, 14.0)
            ))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `le meme identifiant de version peut exister pour deux employeurs`() {
        val history = EmploymentContractHistoryV2(listOf(
            snapshot("v1", 100, null, 13.5, "company-a"),
            snapshot("v1", 100, null, 18.0, "company-b")
        ))

        assertEquals(13.5, history.applicable("company-a", 120)?.contract?.grossHourlyRate ?: 0.0, 0.0)
        assertEquals(18.0, history.applicable("company-b", 120)?.contract?.grossHourlyRate ?: 0.0, 0.0)
    }

    private fun snapshot(
        version: String,
        from: Long,
        to: Long?,
        rate: Double,
        companyId: String = "company-a"
    ) = EmploymentContractSnapshotV2(
        versionId = version,
        sourceId = "user-confirmed",
        effectiveFromEpochDay = from,
        effectiveToEpochDay = to,
        contract = ContractV2(
            id = "contract-$companyId-$version",
            employerId = companyId,
            type = ContractTypeV2.FULL_TIME,
            contractualWeeklyMinutes = 35 * 60,
            grossHourlyRate = rate,
            hireDateEpochDay = null
        ),
        checkedAtMs = 1L
    )
}
