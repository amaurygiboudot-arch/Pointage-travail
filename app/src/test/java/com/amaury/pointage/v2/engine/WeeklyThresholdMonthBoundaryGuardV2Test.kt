package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.SessionStatusV2
import com.amaury.pointage.v2.model.WorkSessionV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class WeeklyThresholdMonthBoundaryGuardV2Test {
    private fun ms(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(Locale.FRANCE).apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun session(id: String, start: Long, end: Long) = WorkSessionV2(
        id = id,
        employerId = "company",
        realArrivalMs = start,
        countedEntryMs = start,
        countedExitMs = end,
        realExitMs = end,
        status = SessionStatusV2.CLOSED
    )

    @Test
    fun outsideMonthTimeThatPushesBoundaryWeekOverThresholdBlocksReliableGross() {
        withParisTimeZone {
            val sessions = listOf(
                session("aug31", ms(2026, Calendar.AUGUST, 31, 8), ms(2026, Calendar.AUGUST, 31, 16)),
                session("sep1", ms(2026, Calendar.SEPTEMBER, 1, 8), ms(2026, Calendar.SEPTEMBER, 1, 16)),
                session("sep2", ms(2026, Calendar.SEPTEMBER, 2, 8), ms(2026, Calendar.SEPTEMBER, 2, 16)),
                session("sep3", ms(2026, Calendar.SEPTEMBER, 3, 8), ms(2026, Calendar.SEPTEMBER, 3, 16)),
                session("sep4", ms(2026, Calendar.SEPTEMBER, 4, 8), ms(2026, Calendar.SEPTEMBER, 4, 16))
            )

            val result = WeeklyThresholdMonthBoundaryGuardV2.assess(
                sessions = sessions,
                acceptedEmployerIds = setOf("company"),
                rangeStartMs = ms(2026, Calendar.SEPTEMBER, 1, 0),
                rangeEndMs = ms(2026, Calendar.OCTOBER, 1, 0),
                weeklyThresholdMinutes = 35 * 60,
                sourceReliable = true,
                nowMs = ms(2026, Calendar.OCTOBER, 6, 0)
            )

            assertFalse(result.reliable)
            assertEquals(1, result.affectedWeeks.size)
            assertEquals(40 * 60, result.affectedWeeks.single().fullWeekPaidMinutes)
            assertEquals(32 * 60, result.affectedWeeks.single().inMonthPaidMinutes)
            assertTrue(result.warnings.contains(WeeklyThresholdMonthBoundaryGuardV2.CONTEXT_WARNING))
        }
    }

    @Test
    fun boundaryWeekBelowThresholdStaysReliable() {
        withParisTimeZone {
            val sessions = listOf(
                session("aug31", ms(2026, Calendar.AUGUST, 31, 8), ms(2026, Calendar.AUGUST, 31, 12)),
                session("sep1", ms(2026, Calendar.SEPTEMBER, 1, 8), ms(2026, Calendar.SEPTEMBER, 1, 16)),
                session("sep2", ms(2026, Calendar.SEPTEMBER, 2, 8), ms(2026, Calendar.SEPTEMBER, 2, 16)),
                session("sep3", ms(2026, Calendar.SEPTEMBER, 3, 8), ms(2026, Calendar.SEPTEMBER, 3, 16))
            )

            val result = WeeklyThresholdMonthBoundaryGuardV2.assess(
                sessions = sessions,
                acceptedEmployerIds = setOf("company"),
                rangeStartMs = ms(2026, Calendar.SEPTEMBER, 1, 0),
                rangeEndMs = ms(2026, Calendar.OCTOBER, 1, 0),
                weeklyThresholdMinutes = 35 * 60,
                sourceReliable = true,
                nowMs = ms(2026, Calendar.OCTOBER, 6, 0)
            )

            assertTrue(result.reliable)
            assertTrue(result.affectedWeeks.isEmpty())
        }
    }

    @Test
    fun thresholdCrossedEntirelyInsideMonthDoesNotCreateArtificialBlock() {
        withParisTimeZone {
            val sessions = (1..5).map { day ->
                session(
                    "sep$day",
                    ms(2026, Calendar.SEPTEMBER, day, 8),
                    ms(2026, Calendar.SEPTEMBER, day, 16)
                )
            }

            val result = WeeklyThresholdMonthBoundaryGuardV2.assess(
                sessions = sessions,
                acceptedEmployerIds = setOf("company"),
                rangeStartMs = ms(2026, Calendar.SEPTEMBER, 1, 0),
                rangeEndMs = ms(2026, Calendar.OCTOBER, 1, 0),
                weeklyThresholdMinutes = 35 * 60,
                sourceReliable = true,
                nowMs = ms(2026, Calendar.OCTOBER, 6, 0)
            )

            assertTrue(result.reliable)
            assertTrue(result.affectedWeeks.isEmpty())
        }
    }

    @Test
    fun unfinishedLastBoundaryWeekFailsClosed() {
        withParisTimeZone {
            val result = WeeklyThresholdMonthBoundaryGuardV2.assess(
                sessions = emptyList(),
                acceptedEmployerIds = setOf("company"),
                rangeStartMs = ms(2026, Calendar.SEPTEMBER, 1, 0),
                rangeEndMs = ms(2026, Calendar.OCTOBER, 1, 0),
                weeklyThresholdMinutes = 35 * 60,
                sourceReliable = true,
                nowMs = ms(2026, Calendar.OCTOBER, 1, 12)
            )

            assertFalse(result.reliable)
            assertTrue(result.warnings.contains(WeeklyThresholdMonthBoundaryGuardV2.FUTURE_CONTEXT_WARNING))
        }
    }

    private fun withParisTimeZone(block: () -> Unit) {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
