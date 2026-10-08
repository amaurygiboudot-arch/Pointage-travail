package com.amaury.pointage.billing

import com.amaury.pointage.v2.model.*
import com.amaury.pointage.v2.engine.PaidWorkAllocationV2
import com.amaury.pointage.v2.engine.WorkTimePolicyV2
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class PaidServiceWorkEvidenceTest {
    private fun ms(value: String) = Instant.parse(value).toEpochMilli()
    private val boundary = ms("2026-02-01T00:00:00Z")
    private val hour = 3_600_000L
    private fun night() = WorkSessionV2("night", "company", boundary - 2 * hour, boundary - 2 * hour,
        boundary + 6 * hour, boundary + 6 * hour,
        pauses = listOf(PauseV2(boundary - hour / 4, boundary + hour / 4, false, EventSourceV2.MANUAL)), status = SessionStatusV2.CLOSED)
    private fun slice(sessions: List<WorkSessionV2>, start: Long, end: Long) =
        PaidServiceWorkEvidence.resolve(sessions, setOf("company"), start, end, boundary + 48 * hour)

    @Test fun crossMonthNightAndBoundaryPauseAreAttributedOnceAcrossAdjacentMonths() {
        val january = slice(listOf(night()), ms("2026-01-01T00:00:00Z"), boundary).single()
        val february = slice(listOf(night()), boundary, ms("2026-03-01T00:00:00Z")).single()
        assertTrue(january.crossesPeriod); assertTrue(february.crossesPeriod)
        assertEquals(2 * hour, january.realPresenceMs); assertEquals(6 * hour, february.realPresenceMs)
        assertEquals(105 * 60_000L, january.paidMs); assertEquals(345 * 60_000L, february.paidMs)
        assertEquals(15 * 60_000L, january.unpaidMs); assertEquals(15 * 60_000L, february.unpaidMs)
        assertEquals(450 * 60_000L, january.paidMs + february.paidMs)
        assertEquals(boundary, january.countedEndMs); assertEquals(boundary, february.countedStartMs)
    }

    @Test fun legacyCountedBoundaryOverlapBlocksPaidReportUntilEmployerRuleIsQualified() {
        val realStart = boundary + 5 * 60_000L
        val session = night().copy(realArrivalMs = realStart, countedEntryMs = boundary - 5 * 60_000L,
            countedExitMs = boundary + hour, realExitMs = boundary + hour, pauses = emptyList())
        val provisional = PaidWorkAllocationV2.paidOverlapResult(session, boundary - 31 * 24 * hour, boundary)
        assertEquals(5 * 60_000L, provisional.paidMs) // Valeur historique préservée.
        assertFalse(provisional.reliable) // L'entrée comptée précède même l'arrivée réelle.
        assertTrue(runCatching {
            slice(listOf(session), boundary - 31 * 24 * hour, boundary)
        }.isFailure)
    }

    @Test fun incompleteUnqualifiedOverlappingAndUnassignedEvidenceStillBlocksPreparation() {
        val bad = listOf(
            listOf(night().copy(realExitMs = null, countedExitMs = null, status = SessionStatusV2.OPEN)),
            listOf(night().copy(pauses = listOf(PauseV2(boundary, boundary + hour, null, EventSourceV2.GPS)))),
            listOf(night(), night().copy(id = "overlap")),
            listOf(night(), night().copy(id = "unassigned", employerId = null))
        )
        bad.forEach { sessions -> assertTrue(runCatching { slice(sessions, boundary, boundary + 24 * hour) }.isFailure) }
    }

    @Test fun legacyFixedDeductionUsesCanonicalProportionalAllocationWithoutNewPauseAssumption() {
        val session = night().copy(pauses = emptyList(), legacyFixedUnpaidPauseMs = hour)
        val january = slice(listOf(session), boundary - 31 * 24 * hour, boundary).single()
        val february = slice(listOf(session), boundary, boundary + 28 * 24 * hour).single()
        assertEquals(105 * 60_000L, january.paidMs)
        assertEquals(315 * 60_000L, february.paidMs)
        assertEquals(7 * hour, january.paidMs + february.paidMs)
    }

    @Test fun knownEntryRepairDoesNotSilentlyCertifyOldCountingPolicyForPaidReport() {
        val session = night().copy(realArrivalMs = boundary + 8 * 60_000L, countedEntryMs = boundary + 15 * 60_000L,
            countedExitMs = boundary + hour, realExitMs = boundary + hour, pauses = emptyList())
        assertEquals(boundary, WorkTimePolicyV2.repairKnownCountedEntry(
            session.realArrivalMs, session.countedEntryMs
        ))
        val provisional = PaidWorkAllocationV2.paidOverlapResult(session, boundary, boundary + 28 * 24 * hour)
        assertEquals(hour, provisional.paidMs) // Relecture historique non destructrice.
        assertFalse(provisional.reliable) // Aucune preuve de règle employeur sur ces faits.
        assertTrue(runCatching {
            slice(listOf(session), boundary, boundary + 28 * 24 * hour)
        }.isFailure)
    }

    @Test fun sixAnnualMonthsWithBoundarySessionsRemainUsableWithoutMonthOmission() {
        fun monthStart(month: Int) = LocalDate.of(2026, month, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val sessions = (1..6).map { month ->
            val next = monthStart(month + 1)
            night().copy(id = "month-$month", realArrivalMs = next - 2 * hour, countedEntryMs = next - 2 * hour,
                realExitMs = next + 6 * hour, countedExitMs = next + 6 * hour, pauses = emptyList())
        }
        val months = (1..6).map { month -> slice(sessions, monthStart(month), monthStart(month + 1)) }
        assertEquals(6, months.count { it.isNotEmpty() && it.sumOf { portion -> portion.paidMs } > 0L })
        assertTrue(months.all { month -> month.any { it.crossesPeriod } })
    }
}
