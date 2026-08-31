package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseScheduleManagerTest {
    @Test
    fun `fenetre de confirmation dure exactement cinq minutes`() {
        val endAt = 1_000_000L
        assertEquals(endAt + 5L * 60_000L, PauseScheduleManager.confirmationDeadline(endAt))
        assertEquals(5L * 60_000L, PauseScheduleManager.CONFIRM_WINDOW_MS)
    }

    @Test
    fun `confirmation reste valide juste avant la limite`() {
        val endAt = 1_000_000L
        val deadline = PauseScheduleManager.confirmationDeadline(endAt)

        assertTrue(PauseScheduleManager.isEndConfirmationPending(endAt, deadline, deadline - 1L))
    }

    @Test
    fun `confirmation expire exactement a la limite`() {
        val endAt = 1_000_000L
        val deadline = PauseScheduleManager.confirmationDeadline(endAt)

        assertFalse(PauseScheduleManager.isEndConfirmationPending(endAt, deadline, deadline))
        assertFalse(PauseScheduleManager.isEndConfirmationPending(endAt, deadline, deadline + 1L))
    }

    @Test
    fun `confirmation invalide sans heure de fin ou deadline`() {
        assertFalse(PauseScheduleManager.isEndConfirmationPending(0L, 10L, 1L))
        assertFalse(PauseScheduleManager.isEndConfirmationPending(10L, 0L, 1L))
    }
}
