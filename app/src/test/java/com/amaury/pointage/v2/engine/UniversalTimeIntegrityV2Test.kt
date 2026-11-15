package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Regression for fictitious professions: facts first, never deduce a job from GPS. */
class UniversalTimeIntegrityV2Test {
    private val min = 60_000L
    private val paris = ZoneId.of("Europe/Paris")
    private fun at(day: String, time: String): Long =
        LocalDateTime.parse("2026-10-05T$time".replace("2026-10-05", day))
            .atZone(paris).toInstant().toEpochMilli()

    private fun raw(start: Long, end: Long, pauses: List<PauseV2> = emptyList()) =
        WorkSessionV2("sim", "company-a", start, start, end, end,
            pauses = pauses, status = SessionStatusV2.CLOSED, timeBasis = TimeBasisV2.REAL_FACTS)

    private fun assertPaid(session: WorkSessionV2, minutes: Long, expectedReliable: Boolean) {
        val time = DefaultTimeEngineV2.calculate(session)
        assertEquals(minutes * min, time.paidWorkMs)
        assertEquals(expectedReliable, time.reliable)
        val payroll = PaidWorkAllocationV2.paidOverlapResult(session, session.countedEntryMs!!, session.countedExitMs!!)
        assertEquals(time.paidWorkMs, payroll.paidMs)
        assertEquals(time.reliable, payroll.reliable)
    }

    @Test fun realStartAndEndAreNotRoundedWithoutAQualifiedRule() {
        val morning = raw(at("2026-10-05","05:11"), at("2026-10-05","13:00"))
        assertPaid(morning, 469, true)
        val short = raw(at("2026-10-05","06:11"), at("2026-10-05","06:20"))
        assertPaid(short, 9, true)
        val end = raw(at("2026-10-05","08:00"), at("2026-10-05","16:07"), listOf(
            PauseV2(at("2026-10-05","12:00"), at("2026-10-05","13:00"), false, EventSourceV2.MANUAL)
        ))
        assertPaid(end, 427, true)
    }

    @Test fun ambiguousAndConflictingPausesNeverProduceAConfirmedPayResult() {
        val start = at("2026-10-05","08:00")
        val end = at("2026-10-05","16:00")
        val from = at("2026-10-05","10:00")
        val until = at("2026-10-05","10:30")
        assertPaid(raw(start,end, listOf(PauseV2(from, until, null, EventSourceV2.MANUAL))),480,false)
        assertPaid(raw(start,end, listOf(PauseV2(from, null, false, EventSourceV2.MANUAL))),480,false)
        assertPaid(raw(start,end, listOf(
            PauseV2(from,until,true,EventSourceV2.MANUAL),
            PauseV2(from,until,false,EventSourceV2.MANUAL)
        )),450,false)
        assertPaid(raw(start,end, listOf(PauseV2(until,from,false,EventSourceV2.MANUAL))),480,false)
    }

    @Test fun historicalPauseCannotBeDeductedTwiceOrSilentlyEraseAWorkDay() {
        val start = at("2026-10-05","08:00")
        val end = at("2026-10-05","16:00")
        val pause = PauseV2(at("2026-10-05","12:00"),at("2026-10-05","12:30"),false,EventSourceV2.MANUAL)
        assertPaid(raw(start,end,listOf(pause)).copy(legacyFixedUnpaidPauseMs=30*min),450,false)
        assertPaid(raw(start,end).copy(legacyFixedUnpaidPauseMs=12*60*min),480,false)
        assertPaid(raw(start,end).copy(legacyFixedUnpaidPauseMs=-30*min),480,false)
        assertPaid(raw(start,end).copy(legacyFixedUnpaidPauseMs=30*min),450,true)
    }

    @Test fun legacyRoundedEntryIsRetainedAsUnverifiedNotRewrittenAsFact() {
        val start = at("2026-10-05","05:11")
        val end = at("2026-10-05","13:00")
        val session = raw(start,end).copy(
            countedEntryMs = at("2026-10-05","05:30"),
            timeBasis = TimeBasisV2.LEGACY_UNVERIFIED
        )
        assertPaid(session, 450, false)
    }

    @Test fun onCallInterventionsAreNotFourteenPaidWorkHours() {
        val start = at("2026-10-05","18:00")
        val end = at("2026-10-06","08:00")
        val segments = listOf(
            WorkSegmentV2(start, at("2026-10-05","20:00"), WorkSegmentKindV2.ON_CALL),
            WorkSegmentV2(at("2026-10-05","20:00"), at("2026-10-05","21:00"), WorkSegmentKindV2.INTERVENTION),
            WorkSegmentV2(at("2026-10-05","21:00"), at("2026-10-06","04:00"), WorkSegmentKindV2.ON_CALL),
            WorkSegmentV2(at("2026-10-06","04:00"), at("2026-10-06","05:00"), WorkSegmentKindV2.INTERVENTION),
            WorkSegmentV2(at("2026-10-06","05:00"), end, WorkSegmentKindV2.ON_CALL)
        )
        assertPaid(raw(start,end).copy(workSegments=segments),120,false)
        assertPaid(raw(start,end).copy(workSegments=segments.dropLast(1)),120,false)
    }

    @Test fun pendingStatusAndPersonalTravelBlockPayrollReliability() {
        val start = at("2026-10-05","08:00")
        val end = at("2026-10-05","16:00")
        val pending = raw(start,end).copy(status=SessionStatusV2.TO_CONFIRM)
        assertFalse(DefaultTimeEngineV2.calculate(pending).reliable)
        assertFalse(PaidWorkAllocationV2.paidOverlapResult(pending,start,end).reliable)
        val personal = raw(start,end).copy(travels=listOf(
            TravelV2(at("2026-10-05","11:00"),at("2026-10-05","12:00"),
                "company-a","company-a",2000.0,TravelClassificationV2.PERSONAL)
        ))
        assertPaid(personal,480,false)
    }

    @Test fun missingRealProofBlocksOtherwiseValidCountedValues() {
        val start = at("2026-10-05","08:00")
        val end = at("2026-10-05","16:00")
        val session = raw(start,end)
        assertFalse(DefaultTimeEngineV2.calculate(session.copy(realArrivalMs=null)).reliable)
        assertFalse(DefaultTimeEngineV2.calculate(session.copy(realExitMs=null)).reliable)
        assertFalse(DefaultTimeEngineV2.calculate(session.copy(realArrivalMs=end+min)).reliable)
    }
}
