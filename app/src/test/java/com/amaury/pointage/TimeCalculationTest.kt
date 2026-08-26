package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class TimeCalculationTest {
    private lateinit var previousZone: TimeZone

    @Before
    fun setUp() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(previousZone)
    }

    @Test
    fun overlappingPausesAreCountedOnlyOnce() {
        val entry = 1_000_000L
        val exit = entry + 8L * 60L * 60L * 1000L
        val pauses = JSONArray()
            .put(JSONObject().put("start", entry + 2L * 60L * 60L * 1000L).put("end", entry + 3L * 60L * 60L * 1000L))
            .put(JSONObject().put("start", entry + 150L * 60L * 1000L).put("end", entry + 210L * 60L * 1000L))
        val item = JSONObject()
            .put("entry", entry)
            .put("exit", exit)
            .put("autoPauseMinutes", 0)
            .put("pauses", pauses)

        assertEquals(90L * 60L * 1000L, WorkTimeMath.pauseDuration(item, exit))
        assertEquals((8L * 60L - 90L) * 60L * 1000L, WorkTimeMath.workedDuration(item, exit))
    }

    @Test
    fun sessionCrossingMonthBoundaryIsSplitBetweenMonths() {
        val convention = requireNotNull(ConventionCatalog.findByIdcc("0292"))
        val entry = at(2026, Calendar.JANUARY, 31, 23, 0)
        val exit = at(2026, Calendar.FEBRUARY, 1, 1, 0)
        val data = session(entry, exit)

        val january = SalaryCalculator.calculate(data, 2026, Calendar.JANUARY, 10.0, convention)
        val february = SalaryCalculator.calculate(data, 2026, Calendar.FEBRUARY, 10.0, convention)

        assertEquals(60L * 60L * 1000L, january.totalWorkedMs)
        assertEquals(60L * 60L * 1000L, february.totalWorkedMs)
        assertEquals(1, january.completedSessions)
        assertEquals(1, february.completedSessions)
    }

    @Test
    fun sundayToMondayDoesNotCarryWeeklyOvertimeIntoNextWeek() {
        val convention = requireNotNull(ConventionCatalog.findByIdcc("0292"))
        val sundayStart = at(2026, Calendar.AUGUST, 30, 23, 0)
        val mondayEnd = at(2026, Calendar.AUGUST, 31, 2, 0)
        val august = SalaryCalculator.calculate(session(sundayStart, mondayEnd), 2026, Calendar.AUGUST, 10.0, convention)

        assertEquals(3L * 60L * 60L * 1000L, august.totalWorkedMs)
        assertEquals(0L, august.overtimeTiers.sumOf { it.durationMs })
    }

    @Test
    fun springDstNightUsesElapsedTimeNotWallClockGuess() {
        val convention = requireNotNull(ConventionCatalog.findByIdcc("0292"))
        // En France, le 29/03/2026 à 02:00 devient 03:00 : 01:00 -> 04:00 = 2 h réelles.
        val entry = at(2026, Calendar.MARCH, 29, 1, 0)
        val exit = at(2026, Calendar.MARCH, 29, 4, 0)
        val result = SalaryCalculator.calculate(session(entry, exit), 2026, Calendar.MARCH, 10.0, convention)
        assertEquals(2L * 60L * 60L * 1000L, result.totalWorkedMs)
    }

    @Test
    fun autumnDstNightCountsRepeatedHour() {
        val convention = requireNotNull(ConventionCatalog.findByIdcc("0292"))
        // En France, le 25/10/2026 à 03:00 revient à 02:00 : 01:00 -> 04:00 = 4 h réelles.
        val entry = at(2026, Calendar.OCTOBER, 25, 1, 0)
        val exit = at(2026, Calendar.OCTOBER, 25, 4, 0)
        val result = SalaryCalculator.calculate(session(entry, exit), 2026, Calendar.OCTOBER, 10.0, convention)
        assertEquals(4L * 60L * 60L * 1000L, result.totalWorkedMs)
    }

    private fun session(entry: Long, exit: Long): JSONArray = JSONArray().put(
        JSONObject()
            .put("entry", entry)
            .put("exit", exit)
            .put("autoPauseMinutes", 0)
            .put("pauses", JSONArray())
    )

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"), Locale.FRANCE).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
