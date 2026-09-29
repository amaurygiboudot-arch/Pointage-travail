package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanyPauseDurationV2Test {
    @Test
    fun `long split shift pause keeps its actual duration`() {
        assertEquals(420, CompanyPauseSettingsV2.PauseSlot(8 * 60, 15 * 60).durationMinutes)
    }

    @Test
    fun `overnight pause keeps its actual duration`() {
        assertEquals(420, CompanyPauseSettingsV2.PauseSlot(22 * 60, 5 * 60).durationMinutes)
        assertEquals(0, CompanyPauseSettingsV2.PauseSlot(-1, 5 * 60).durationMinutes)
    }
}
