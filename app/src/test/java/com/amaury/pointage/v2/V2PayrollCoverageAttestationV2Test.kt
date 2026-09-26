package com.amaury.pointage.v2

import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.*
import org.junit.Test

class V2PayrollCoverageAttestationV2Test {
    private val base = session("a", 1_000L, 2_000L)

    @Test fun fingerprintDoesNotDependOnSessionOrder() {
        val one = session("one", 1_000L, 2_000L)
        val two = session("two", 3_000L, 4_000L)
        assertEquals(
            V2PayrollCoverageFingerprintV2.compute(listOf(one, two)),
            V2PayrollCoverageFingerprintV2.compute(listOf(two, one))
        )
    }

    @Test fun pauseCorrectionInvalidatesFingerprint() {
        val before = base.copy(pauses = listOf(PauseV2(1_200L, 1_300L, false, EventSourceV2.MANUAL)))
        val after = base.copy(pauses = listOf(PauseV2(1_200L, 1_300L, true, EventSourceV2.MANUAL)))
        assertNotEquals(
            V2PayrollCoverageFingerprintV2.compute(listOf(before)),
            V2PayrollCoverageFingerprintV2.compute(listOf(after))
        )
    }

    @Test fun validAttestationCoversRequestedRange() {
        val fp = V2PayrollCoverageFingerprintV2.compute(listOf(base))
        val result = V2PayrollCoverageResolverV2.resolve(
            attestations = listOf(attestation(fp, 0, 20)),
            sessions = listOf(base),
            sourceReliable = true,
            sourceWarnings = emptyList(),
            employerId = "company",
            requiredStartEpochDay = 5,
            requiredEndEpochDay = 10,
            nowMs = 20_000L
        )
        assertTrue(result.reliable)
        assertNotNull(result.attestation)
    }

    @Test fun reliableStoreAloneNeverProvesExhaustiveCoverage() {
        val result = V2PayrollCoverageResolverV2.resolve(
            attestations = emptyList(),
            sessions = listOf(base),
            sourceReliable = true,
            sourceWarnings = emptyList(),
            employerId = "company",
            requiredStartEpochDay = 5,
            requiredEndEpochDay = 10,
            nowMs = 20_000L
        )
        assertFalse(result.reliable)
        assertTrue(result.warnings.contains(V2PayrollCoverageResolverV2.MISSING_WARNING))
    }

    @Test fun changedHistoryInvalidatesAttestation() {
        val fp = V2PayrollCoverageFingerprintV2.compute(listOf(base))
        val changed = base.copy(realExitMs = 2_100L, countedExitMs = 2_100L)
        val result = V2PayrollCoverageResolverV2.resolve(
            attestations = listOf(attestation(fp, 0, 20)),
            sessions = listOf(changed),
            sourceReliable = true,
            sourceWarnings = emptyList(),
            employerId = "company",
            requiredStartEpochDay = 5,
            requiredEndEpochDay = 10,
            nowMs = 20_000L
        )
        assertFalse(result.reliable)
        assertTrue(result.warnings.contains(V2PayrollCoverageResolverV2.CHANGED_WARNING))
    }

    @Test fun tooNarrowCoverageIsRejected() {
        val fp = V2PayrollCoverageFingerprintV2.compute(listOf(base))
        val result = V2PayrollCoverageResolverV2.resolve(
            attestations = listOf(attestation(fp, 6, 9)),
            sessions = listOf(base),
            sourceReliable = true,
            sourceWarnings = emptyList(),
            employerId = "company",
            requiredStartEpochDay = 5,
            requiredEndEpochDay = 10,
            nowMs = 20_000L
        )
        assertFalse(result.reliable)
    }

    @Test fun futureAttestationIsRejected() {
        val fp = V2PayrollCoverageFingerprintV2.compute(listOf(base))
        val result = V2PayrollCoverageResolverV2.resolve(
            attestations = listOf(attestation(fp, 0, 20).copy(checkedAtMs = 30_000L)),
            sessions = listOf(base),
            sourceReliable = true,
            sourceWarnings = emptyList(),
            employerId = "company",
            requiredStartEpochDay = 5,
            requiredEndEpochDay = 10,
            nowMs = 20_000L
        )
        assertFalse(result.reliable)
    }

    @Test fun unreliableRuntimeBlocksEvenWithMatchingAttestation() {
        val fp = V2PayrollCoverageFingerprintV2.compute(listOf(base))
        val result = V2PayrollCoverageResolverV2.resolve(
            attestations = listOf(attestation(fp, 0, 20)),
            sessions = listOf(base),
            sourceReliable = false,
            sourceWarnings = listOf("runtime-broken"),
            employerId = "company",
            requiredStartEpochDay = 5,
            requiredEndEpochDay = 10,
            nowMs = 20_000L
        )
        assertFalse(result.reliable)
        assertTrue(result.warnings.contains("runtime-broken"))
        assertTrue(result.warnings.contains(V2PayrollCoverageResolverV2.SOURCE_WARNING))
    }

    private fun attestation(fp: String, start: Long, end: Long) =
        V2PayrollCoverageAttestationV2(
            employerId = "company",
            coveredStartEpochDay = start,
            coveredEndEpochDay = end,
            checkedAtMs = 10_000L,
            timeZoneId = "Europe/Paris",
            sourceId = "coverage-test",
            historyFingerprint = fp
        )

    private fun session(id: String, start: Long, end: Long) =
        WorkSessionV2(
            id = id,
            employerId = "company",
            realArrivalMs = start,
            countedEntryMs = start,
            countedExitMs = end,
            realExitMs = end,
            status = SessionStatusV2.CLOSED
        )
}
