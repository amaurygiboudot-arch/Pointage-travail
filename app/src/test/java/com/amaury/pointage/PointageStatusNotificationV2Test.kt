package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class PointageStatusNotificationV2Test {

    @Test
    fun `opacity snaps to supported user levels`() {
        assertEquals(25, PointageStatusNotificationV2.opacityBucket(0))
        assertEquals(25, PointageStatusNotificationV2.opacityBucket(37))
        assertEquals(50, PointageStatusNotificationV2.opacityBucket(38))
        assertEquals(75, PointageStatusNotificationV2.opacityBucket(74))
        assertEquals(100, PointageStatusNotificationV2.opacityBucket(100))
        assertEquals(100, PointageStatusNotificationV2.opacityBucket(140))
    }

    @Test
    fun `day and night palettes remain distinct for every status`() {
        PointageStatusNotificationV2.DisplayState.values().forEach { state ->
            val day = PointageStatusNotificationV2.resolveAccentColor(state, dark = false)
            val night = PointageStatusNotificationV2.resolveAccentColor(state, dark = true)
            org.junit.Assert.assertNotEquals(day, night)
        }
    }

    @Test
    fun `no persistent status icon after clock out`() {
        org.junit.Assert.assertFalse(
            PointageStatusNotificationV2.shouldShowIndicator(IconSwitcher.IconState.DEFAULT))
        org.junit.Assert.assertTrue(
            PointageStatusNotificationV2.shouldShowIndicator(IconSwitcher.IconState.WORKING))
        org.junit.Assert.assertTrue(
            PointageStatusNotificationV2.shouldShowIndicator(IconSwitcher.IconState.PAUSED))
        org.junit.Assert.assertTrue(PointageStatusNotificationV2.shouldShowIndicator(null))
    }

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
