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

        assertEquals(90L * 60L * 1000L, PointageStore.pauseDuration(item, exit))
        assertEquals((8L * 60L - 90L) * 60L * 1000L, PointageStore.workedDuration(item, exit))
    }

    @Test
    fun sessionCrossingMonthBoundaryIsSplitBetweenMonths() {
        val convention = requireNotNull(ConventionCatalog.findByIdcc("0292"))
        val entry = at(2026, Calendar.JANUARY, 31, 23, 0)
        val exit = at(2026, Calendar.FEBRUARY, 1, 1, 0)
        val data = JSONArray().put(
            JSONObject()
                .put("entry", entry)
                .put("exit", exit)
                .put("autoPauseMinutes", 0)
                .put("pauses", JSONArray())
        )

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
        val data = JSONArray().put(
            JSONObject()
                .put("entry", sundayStart)
                .put("exit", mondayEnd)
                .put("autoPauseMinutes", 0)
                .put("pauses", JSONArray())
        )

        val august = SalaryCalculator.calculate(data, 2026, Calendar.AUGUST, 10.0, convention)

        assertEquals(3L * 60L * 60L * 1000L, august.totalWorkedMs)
        assertEquals(0L, august.overtimeTiers.sumOf { it.durationMs })
    }

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Europe/Paris"), Locale.FRANCE).apply {
            clear()
            set(year, month, day, hour, minute, 0)
        }.timeInMillis
}
