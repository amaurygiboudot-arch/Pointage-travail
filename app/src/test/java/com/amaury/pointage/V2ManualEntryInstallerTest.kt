package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class V2ManualEntryInstallerTest {
    private fun day() = Calendar.getInstance(
        TimeZone.getTimeZone("Europe/Paris"),
        Locale.FRANCE
    ).apply {
        set(2026, Calendar.SEPTEMBER, 17, 0, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test
    fun `manual time accepts colon or h and rejects invalid values`() {
        val day = day()

        assertEquals(
            5L * 60L * 60L * 1000L,
            V2ManualEntryInstaller.parseTime(day, "05:00")!! - day.timeInMillis
        )
        assertEquals(
            13L * 60L * 60L * 1000L + 30L * 60L * 1000L,
            V2ManualEntryInstaller.parseTime(day, "13h30")!! - day.timeInMillis
        )
        assertNull(V2ManualEntryInstaller.parseTime(day, "24:00"))
        assertNull(V2ManualEntryInstaller.parseTime(day, "8:7"))
    }

    @Test
    fun `manual range supports overnight work but rejects equal times`() {
        val day = day()
        val start = V2ManualEntryInstaller.parseTime(day, "21:00")!!
        val rawEnd = V2ManualEntryInstaller.parseTime(day, "06:00")!!

        assertEquals(
            9L * 60L * 60L * 1000L,
            V2ManualEntryInstaller.normalizeEnd(
                start,
                rawEnd,
                TimeZone.getTimeZone("Europe/Paris")
            )!! - start
        )
        assertNull(V2ManualEntryInstaller.normalizeEnd(start, start))
    }
}
