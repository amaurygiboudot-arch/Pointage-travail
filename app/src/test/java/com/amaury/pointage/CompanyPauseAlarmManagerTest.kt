package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class CompanyPauseAlarmManagerTest {
    @Test
    fun `une alarme de debut retardee conserve le debut programme`() {
        val scheduledStart = at(10, 0)
        val scheduledEnd = at(11, 0)

        val result = CompanyPauseAlarmManager.automaticStartTime(
            realEntryMs = at(5, 0),
            deliveredAtMs = at(10, 10),
            scheduledAtMs = scheduledStart,
            windowEndAtMs = scheduledEnd
        )

        assertEquals(scheduledStart, result)
    }

    @Test
    fun `une alarme de debut livree apres la pause est ignoree`() {
        val result = CompanyPauseAlarmManager.automaticStartTime(
            realEntryMs = at(5, 0),
            deliveredAtMs = at(11, 10),
            scheduledAtMs = at(10, 0),
            windowEndAtMs = at(11, 0)
        )

        assertNull(result)
    }

    @Test
    fun `une alarme de fin retardee conserve la fin programmee`() {
        assertEquals(
            at(11, 0),
            CompanyPauseAlarmManager.automaticEndTime(
                pauseStartMs = at(10, 0),
                deliveredAtMs = at(11, 10),
                scheduledAtMs = at(11, 0)
            )
        )
    }

    private fun at(hour: Int, minute: Int): Long = Calendar.getInstance(Locale.FRANCE).apply {
        set(2026, Calendar.SEPTEMBER, 3, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
