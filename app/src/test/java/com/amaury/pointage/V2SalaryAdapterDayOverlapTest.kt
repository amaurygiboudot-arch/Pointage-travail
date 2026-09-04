package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class V2SalaryAdapterDayOverlapTest {
    private fun localMs(year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(Locale.FRANCE).apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun fridayNightCountsOnlySaturdayPartAsSaturday() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            val start = localMs(2026, Calendar.SEPTEMBER, 4, 22)
            val end = localMs(2026, Calendar.SEPTEMBER, 5, 6)

            assertEquals(6L * 60L * 60L * 1000L, V2SalaryAdapter.dayOverlap(start, end, Calendar.SATURDAY))
            assertEquals(0L, V2SalaryAdapter.dayOverlap(start, end, Calendar.SUNDAY))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test
    fun saturdayNightSplitsSaturdayAndSunday() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
            val start = localMs(2026, Calendar.SEPTEMBER, 5, 22)
            val end = localMs(2026, Calendar.SEPTEMBER, 6, 6)

            assertEquals(2L * 60L * 60L * 1000L, V2SalaryAdapter.dayOverlap(start, end, Calendar.SATURDAY))
            assertEquals(6L * 60L * 60L * 1000L, V2SalaryAdapter.dayOverlap(start, end, Calendar.SUNDAY))
        } finally {
            TimeZone.setDefault(previous)
        }
    }
}
