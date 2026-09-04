package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.EventSourceV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class PaidWorkAllocationV2Test {
    private fun ms(year:Int,month:Int,day:Int,hour:Int,minute:Int=0):Long = Calendar.getInstance(Locale.FRANCE).apply {
        set(year,month,day,hour,minute,0);set(Calendar.MILLISECOND,0)
    }.timeInMillis

    private fun session(start:Long,end:Long,pauses:List<PauseV2> = emptyList()) = WorkSessionV2(
        id="test", employerId="company", realArrivalMs=start, countedEntryMs=start,
        countedExitMs=end, realExitMs=end, pauses=pauses, status=SessionStatusV2.CLOSED
    )

    @Test
    fun sundayToMondayIsSplitBetweenTwoIsoWeeks() {
        val previous=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            val start=ms(2026,Calendar.SEPTEMBER,6,22)
            val end=ms(2026,Calendar.SEPTEMBER,7,6)
            val slices=PaidWorkAllocationV2.splitByIsoWeek(session(start,end),start,end)

            assertEquals(2,slices.size)
            assertEquals(2L*60L*60L*1000L,slices[0].paidMs)
            assertEquals(6L*60L*60L*1000L,slices[1].paidMs)
        } finally { TimeZone.setDefault(previous) }
    }

    @Test
    fun nightShiftKeepsFirstThirtyMinutesOfRecordedPausePaid() {
        val previous=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            val start=ms(2026,Calendar.SEPTEMBER,6,22)
            val end=ms(2026,Calendar.SEPTEMBER,7,6)
            val pause=PauseV2(
                startMs=ms(2026,Calendar.SEPTEMBER,7,2),
                endMs=ms(2026,Calendar.SEPTEMBER,7,3),
                paid=false, source=EventSourceV2.MANUAL, status=DecisionStatusV2.CONFIRMED
            )
            val slices=PaidWorkAllocationV2.splitByIsoWeek(session(start,end,listOf(pause)),start,end)

            assertEquals(2L*60L*60L*1000L,slices[0].paidMs)
            assertEquals(5L*60L*60L*1000L+30L*60L*1000L,slices[1].paidMs)
        } finally { TimeZone.setDefault(previous) }
    }

    @Test
    fun dayShiftStillDeductsItsRecordedUnpaidPauseInFull() {
        val previous=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            val start=ms(2026,Calendar.SEPTEMBER,8,8)
            val end=ms(2026,Calendar.SEPTEMBER,8,16)
            val pause=PauseV2(
                startMs=ms(2026,Calendar.SEPTEMBER,8,12),
                endMs=ms(2026,Calendar.SEPTEMBER,8,13),
                paid=false, source=EventSourceV2.MANUAL, status=DecisionStatusV2.CONFIRMED
            )

            assertEquals(7L*60L*60L*1000L,PaidWorkAllocationV2.paidOverlap(session(start,end,listOf(pause)),start,end))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test
    fun monthBoundaryKeepsOnlyPaidTimeInsideRequestedMonth() {
        val previous=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            val start=ms(2026,Calendar.SEPTEMBER,30,22)
            val end=ms(2026,Calendar.OCTOBER,1,6)
            val octoberStart=ms(2026,Calendar.OCTOBER,1,0)

            assertEquals(2L*60L*60L*1000L,PaidWorkAllocationV2.paidOverlap(session(start,end),start,octoberStart))
            assertEquals(6L*60L*60L*1000L,PaidWorkAllocationV2.paidOverlap(session(start,end),octoberStart,end))
        } finally { TimeZone.setDefault(previous) }
    }
}
