package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ContractSegmentPaidWorkV2Test {
    private val zone = ZoneId.of("Europe/Paris")

    @Test
    fun `une session traversant le changement de contrat est coupee a minuit`() {
        val changeDay = LocalDate.of(2026, 9, 15).toEpochDay()
        val segments = listOf(
            segment("v1", LocalDate.of(2026, 9, 1).toEpochDay(), changeDay - 1, 13.0),
            segment("v2", changeDay, LocalDate.of(2026, 9, 30).toEpochDay(), 14.0)
        )
        val session = session(
            start = ms(2026, 9, 14, 22),
            end = ms(2026, 9, 15, 6)
        )

        val result = ContractSegmentPaidWorkAllocatorV2.allocate(
            sessions = listOf(session),
            segments = segments,
            acceptedEmployerIds = setOf("company"),
            sourceReliable = true,
            zoneId = zone
        )

        assertTrue(result.reliable)
        assertEquals(2, result.segments.size)
        assertEquals(2L * 60L * 60L * 1000L, result.segments[0].paidMs)
        assertEquals(6L * 60L * 60L * 1000L, result.segments[1].paidMs)
        assertEquals(8L * 60L * 60L * 1000L, result.totalPaidMs)
        assertEquals(13.0, result.segments[0].contract.grossHourlyRate!!, 0.0)
        assertEquals(14.0, result.segments[1].contract.grossHourlyRate!!, 0.0)
    }

    @Test
    fun `pause non payee traversant la frontiere est deduite de chaque cote`() {
        val changeDay = LocalDate.of(2026, 9, 15).toEpochDay()
        val segments = listOf(
            segment("v1", LocalDate.of(2026, 9, 1).toEpochDay(), changeDay - 1, 13.0),
            segment("v2", changeDay, LocalDate.of(2026, 9, 30).toEpochDay(), 14.0)
        )
        val pause = PauseV2(
            startMs = ms(2026, 9, 14, 23),
            endMs = ms(2026, 9, 15, 1),
            paid = false,
            source = EventSourceV2.MANUAL,
            status = DecisionStatusV2.CONFIRMED
        )
        val session = session(ms(2026, 9, 14, 22), ms(2026, 9, 15, 6), listOf(pause))

        val result = ContractSegmentPaidWorkAllocatorV2.allocate(
            sessions = listOf(session),
            segments = segments,
            acceptedEmployerIds = setOf("company"),
            sourceReliable = true,
            zoneId = zone
        )

        assertTrue(result.reliable)
        assertEquals(1L * 60L * 60L * 1000L, result.segments[0].paidMs)
        assertEquals(5L * 60L * 60L * 1000L, result.segments[1].paidMs)
        assertEquals(6L * 60L * 60L * 1000L, result.totalPaidMs)
    }

    @Test
    fun `pause non qualifiee rend le segment concerne non fiable sans inventer sa classe`() {
        val startDay = LocalDate.of(2026, 9, 1).toEpochDay()
        val endDay = LocalDate.of(2026, 9, 30).toEpochDay()
        val pause = PauseV2(
            startMs = ms(2026, 9, 8, 12),
            endMs = ms(2026, 9, 8, 13),
            paid = null,
            source = EventSourceV2.MANUAL,
            status = DecisionStatusV2.TO_CONFIRM
        )

        val result = ContractSegmentPaidWorkAllocatorV2.allocate(
            sessions = listOf(session(ms(2026, 9, 8, 8), ms(2026, 9, 8, 16), listOf(pause))),
            segments = listOf(segment("v1", startDay, endDay, 13.0)),
            acceptedEmployerIds = setOf("company"),
            sourceReliable = true,
            zoneId = zone
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.contains(ContractSegmentPaidWorkAllocatorV2.UNRELIABLE_SESSION_WARNING))
    }

    @Test
    fun `source runtime non fiable contamine tous les segments`() {
        val startDay = LocalDate.of(2026, 9, 1).toEpochDay()
        val endDay = LocalDate.of(2026, 9, 30).toEpochDay()
        val result = ContractSegmentPaidWorkAllocatorV2.allocate(
            sessions = listOf(session(ms(2026, 9, 8, 8), ms(2026, 9, 8, 16))),
            segments = listOf(segment("v1", startDay, endDay, 13.0)),
            acceptedEmployerIds = setOf("company"),
            sourceReliable = false,
            zoneId = zone
        )

        assertFalse(result.reliable)
        assertTrue(result.warnings.contains(ContractSegmentPaidWorkAllocatorV2.UNRELIABLE_SOURCE_WARNING))
    }

    private fun segment(version: String, start: Long, end: Long, rate: Double) =
        EmploymentContractCoverageSegmentV2(
            startEpochDay = start,
            endEpochDay = end,
            snapshot = EmploymentContractSnapshotV2(
                versionId = version,
                sourceId = "contract-$version",
                effectiveFromEpochDay = start,
                effectiveToEpochDay = end,
                contract = ContractV2(
                    id = "contract-$version",
                    employerId = "company",
                    type = ContractTypeV2.FULL_TIME,
                    contractualWeeklyMinutes = 35 * 60,
                    grossHourlyRate = rate,
                    hireDateEpochDay = LocalDate.of(2020, 1, 1).toEpochDay()
                ),
                checkedAtMs = 1L
            )
        )

    private fun session(start: Long, end: Long, pauses: List<PauseV2> = emptyList()) = WorkSessionV2(
        id = "session-$start",
        employerId = "company",
        realArrivalMs = start,
        countedEntryMs = start,
        countedExitMs = end,
        realExitMs = end,
        pauses = pauses,
        status = SessionStatusV2.CLOSED
    )

    private fun ms(year: Int, month: Int, day: Int, hour: Int): Long =
        LocalDate.of(year, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
}
