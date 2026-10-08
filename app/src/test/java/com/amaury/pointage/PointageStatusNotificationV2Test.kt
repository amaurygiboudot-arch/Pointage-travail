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
    fun `chronometer uses canonical entry only for an active or attention state`() {
        val now = 10_000L
        assertEquals(
            1_000L,
            PointageStatusNotificationV2.chronometerStartMs(
                PointageStatusNotificationV2.DisplayState.GREEN,
                1_000L,
                now
            )
        )
        assertEquals(
            1_000L,
            PointageStatusNotificationV2.chronometerStartMs(
                PointageStatusNotificationV2.DisplayState.ORANGE,
                1_000L,
                now
            )
        )
        assertEquals(
            null,
            PointageStatusNotificationV2.chronometerStartMs(
                PointageStatusNotificationV2.DisplayState.RED,
                1_000L,
                now
            )
        )
    }

    @Test
    fun `chronometer fails closed when the entry is missing invalid or in the future`() {
        val now = 10_000L
        listOf<Long?>(null, 0L, -1L, 10_001L).forEach { start ->
            assertEquals(
                null,
                PointageStatusNotificationV2.chronometerStartMs(
                    PointageStatusNotificationV2.DisplayState.GREEN,
                    start,
                    now
                )
            )
        }
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

}
