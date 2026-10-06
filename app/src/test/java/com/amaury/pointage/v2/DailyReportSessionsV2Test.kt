package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.PaidWorkAllocationV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class DailyReportSessionsV2Test {
    private val zone = ZoneId.of("Europe/Paris")
    private val hour = 3_600_000L
    private fun at(value: String) = LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
    private fun bounds(day: String): Pair<Long, Long> {
        val date = LocalDate.parse(day)
        return date.atStartOfDay(zone).toInstant().toEpochMilli() to
            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    private fun session(start: String, end: String, pauses: List<PauseV2> = emptyList()) =
        WorkSessionV2("night", "employer", at(start), at(start), at(end), at(end), pauses,
            status = SessionStatusV2.CLOSED)
    private fun rows(s: WorkSessionV2, day: String) = bounds(day).let {
        DailyReportSessionsV2.rows(listOf(s), it.first, it.second)
    }
    private fun pause(start: String, end: String, paid: Boolean?) =
        PauseV2(at(start), at(end), paid, EventSourceV2.MANUAL)

    @Test fun `night crossing month allocates each day's work and clips qualified pauses`() {
        val s = session("2026-01-31T22:00", "2026-02-01T06:00", listOf(
            pause("2026-01-31T23:30", "2026-02-01T00:30", false),
            pause("2026-02-01T02:00", "2026-02-01T02:15", true)))
        val first = rows(s, "2026-01-31").single()
        val second = rows(s, "2026-02-01").single()
        assertEquals(90 * 60_000L, first.paidMs)
        assertEquals(330 * 60_000L, second.paidMs)
        assertEquals(at("2026-02-01T00:00"), first.endMs)
        assertEquals(first.endMs, second.startMs)
        assertEquals(30 * 60_000L, first.explicitUnpaidMs)
        assertEquals(30 * 60_000L, second.explicitUnpaidMs)
        assertEquals(listOf(false), first.pauses.map { it.paid })
        assertEquals(listOf(false, true), second.pauses.map { it.paid })
        assertEquals(at("2026-02-01T00:00"), first.pauses.single().endMs)
        assertEquals(at("2026-02-01T00:00"), second.pauses.first().startMs)
        assertEquals(7 * hour, first.paidMs + second.paidMs)
        assertEquals(at("2026-01-31T23:30"), s.pauses.first().startMs)
        assertEquals(at("2026-02-01T00:30"), s.pauses.first().endMs)
    }

    @Test fun `multi day and exact midnight never duplicate a boundary`() {
        val s = session("2026-01-30T22:00", "2026-02-02T00:00")
        val durations = listOf("2026-01-30", "2026-01-31", "2026-02-01", "2026-02-02")
            .map { day -> rows(s, day).sumOf { it.paidMs } }
        assertEquals(listOf(2 * hour, 24 * hour, 24 * hour, 0L), durations)
    }

    @Test fun `DST spring and autumn use elapsed time within civil day bounds`() {
        for ((start, end, firstDay, secondDay, totalHours) in listOf(
            listOf("2026-03-28T22:00", "2026-03-29T06:00", "2026-03-28", "2026-03-29", "7"),
            listOf("2026-10-24T22:00", "2026-10-25T06:00", "2026-10-24", "2026-10-25", "9")
        )) {
            val s = session(start, end)
            val first = rows(s, firstDay).single()
            val second = rows(s, secondDay).single()
            assertEquals(2 * hour, first.paidMs)
            assertEquals(totalHours.toLong() * hour, first.paidMs + second.paidMs)
            val (dayStart, dayEnd) = bounds(secondDay)
            assertEquals((if (totalHours == "7") 23 else 25) * hour, dayEnd - dayStart)
        }
    }

    @Test fun `pause crossing clock change deducts elapsed qualified time`() {
        for (case in listOf(
            listOf("2026-03-28T22:00", "2026-03-29T06:00", "2026-03-29", "2026-03-29T01:30", "2026-03-29T03:30", "1"),
            listOf("2026-10-24T22:00", "2026-10-25T06:00", "2026-10-25", "2026-10-25T01:30", "2026-10-25T03:30", "3")
        )) {
            val row = rows(session(case[0], case[1], listOf(pause(case[3], case[4], false))), case[2]).single()
            assertEquals(case[5].toLong() * hour, row.explicitUnpaidMs)
            assertEquals(4 * hour, row.paidMs)
        }
    }

    @Test fun `fixed historical deduction follows canonical allocation without fictional pauses`() {
        val s = session("2026-01-31T22:00", "2026-02-01T06:00").copy(legacyFixedUnpaidPauseMs = hour)
        val first = rows(s, "2026-01-31").single()
        val second = rows(s, "2026-02-01").single()
        assertEquals(15 * 60_000L, first.allocatedFixedUnpaidMs)
        assertEquals(45 * 60_000L, second.allocatedFixedUnpaidMs)
        assertTrue(first.pauses.isEmpty())
        assertTrue(second.pauses.isEmpty())
        assertEquals(7 * hour, first.paidMs + second.paidMs)
        assertEquals(PaidWorkAllocationV2.paidOverlap(s, first.startMs, first.endMs), first.paidMs)
    }

    @Test(expected = IllegalStateException::class)
    fun `unqualified pause blocks final report`() {
        rows(session("2026-01-31T22:00", "2026-02-01T06:00", listOf(
            pause("2026-02-01T02:00", "2026-02-01T02:15", null))), "2026-01-31")
    }

    @Test(expected = IllegalStateException::class)
    fun `missing counted exit blocks instead of falling back to real time`() {
        rows(session("2026-01-31T22:00", "2026-02-01T06:00").copy(countedExitMs = null), "2026-02-01")
    }

    @Test fun `open session remains outside completed report`() {
        assertTrue(rows(session("2026-01-31T22:00", "2026-02-01T06:00")
            .copy(status = SessionStatusV2.OPEN, realExitMs = null, countedExitMs = null), "2026-01-31").isEmpty())
    }

    @Test fun `known counted entry repair agrees with canonical paid interval`() {
        val s = session("2026-02-01T06:08", "2026-02-01T13:00").copy(countedEntryMs = at("2026-02-01T06:15"))
        val row = rows(s, "2026-02-01").single()
        assertEquals(at("2026-02-01T06:00"), row.startMs)
        assertEquals(7 * hour, row.paidMs)
    }
}
