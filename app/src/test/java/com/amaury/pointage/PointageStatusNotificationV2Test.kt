package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class PointageStatusNotificationV2Test {

    @Test
    fun `red when no session is active`() {
        assertEquals(
            PointageStatusNotificationV2.DisplayState.RED,
            PointageStatusNotificationV2.resolveDisplayState(
                IconSwitcher.IconState.DEFAULT,
                hasPendingGpsEvent = false
            )
        )
    }

    @Test
    fun `green when work session is active`() {
        assertEquals(
            PointageStatusNotificationV2.DisplayState.GREEN,
            PointageStatusNotificationV2.resolveDisplayState(
                IconSwitcher.IconState.WORKING,
                hasPendingGpsEvent = false
            )
        )
    }

    @Test
    fun `orange for pause pending event or unreliable runtime`() {
        assertEquals(
            PointageStatusNotificationV2.DisplayState.ORANGE,
            PointageStatusNotificationV2.resolveDisplayState(
                IconSwitcher.IconState.PAUSED,
                hasPendingGpsEvent = false
            )
        )
        assertEquals(
            PointageStatusNotificationV2.DisplayState.ORANGE,
            PointageStatusNotificationV2.resolveDisplayState(
                IconSwitcher.IconState.WORKING,
                hasPendingGpsEvent = true
            )
        )
        assertEquals(
            PointageStatusNotificationV2.DisplayState.ORANGE,
            PointageStatusNotificationV2.resolveDisplayState(
                iconState = null,
                hasPendingGpsEvent = false
            )
        )
    }
    @Test
    fun `live update chip keeps the requested traffic light state`() {
        assertEquals("×", PointageStatusNotificationV2.shortCriticalText(PointageStatusNotificationV2.DisplayState.RED))
        assertEquals("✓", PointageStatusNotificationV2.shortCriticalText(PointageStatusNotificationV2.DisplayState.GREEN))
        assertEquals("Ⅱ", PointageStatusNotificationV2.shortCriticalText(PointageStatusNotificationV2.DisplayState.ORANGE))
    }

}
