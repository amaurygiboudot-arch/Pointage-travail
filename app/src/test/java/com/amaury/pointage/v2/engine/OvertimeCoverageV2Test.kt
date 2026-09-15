package com.amaury.pointage.v2.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OvertimeCoverageV2Test {
    private val limit = 35 * 60

    @Test
    fun noOvertimeNeedsNoTier() {
        assertTrue(OvertimeCoverageV2.isFullyCovered(limit, limit, emptyList()))
    }

    @Test
    fun overtimeWithoutTierIsNotCovered() {
        assertFalse(OvertimeCoverageV2.isFullyCovered(limit, 40 * 60, emptyList()))
    }

    @Test
    fun continuousConfirmedTierCoversAllOvertime() {
        assertTrue(
            OvertimeCoverageV2.isFullyCovered(
                limit,
                40 * 60,
                listOf(OvertimeTierV2(limit, null, 1.25))
            )
        )
    }

    @Test
    fun gapBeforeFirstTierLeavesGrossUnresolved() {
        assertFalse(
            OvertimeCoverageV2.isFullyCovered(
                limit,
                40 * 60,
                listOf(OvertimeTierV2(37 * 60, null, 1.25))
            )
        )
    }

    @Test
    fun gapBetweenTiersLeavesGrossUnresolved() {
        assertFalse(
            OvertimeCoverageV2.isFullyCovered(
                limit,
                45 * 60,
                listOf(
                    OvertimeTierV2(limit, 40 * 60, 1.25),
                    OvertimeTierV2(41 * 60, null, 1.50)
                )
            )
        )
    }

    @Test
    fun overlappingTiersAreAmbiguousAndNotReliable() {
        assertFalse(
            OvertimeCoverageV2.isFullyCovered(
                limit,
                45 * 60,
                listOf(
                    OvertimeTierV2(limit, 43 * 60, 1.25),
                    OvertimeTierV2(42 * 60, null, 1.50)
                )
            )
        )
    }

    @Test
    fun openEndedTierCannotHideLaterOverlap() {
        assertFalse(
            OvertimeCoverageV2.isFullyCovered(
                limit,
                45 * 60,
                listOf(
                    OvertimeTierV2(limit, null, 1.25),
                    OvertimeTierV2(43 * 60, null, 1.50)
                )
            )
        )
    }

    @Test
    fun tierStartingBelowRegularLimitIsRejected() {
        assertFalse(
            OvertimeCoverageV2.isFullyCovered(
                limit,
                40 * 60,
                listOf(OvertimeTierV2(34 * 60, null, 1.25))
            )
        )
    }

    @Test
    fun adjacentTiersCoverWithoutGapOrOverlap() {
        assertTrue(
            OvertimeCoverageV2.isFullyCovered(
                limit,
                45 * 60,
                listOf(
                    OvertimeTierV2(limit, 43 * 60, 1.25),
                    OvertimeTierV2(43 * 60, null, 1.50)
                )
            )
        )
    }

    @Test
    fun everyWeekMustBeCovered() {
        assertFalse(
            OvertimeCoverageV2.areWeeksFullyCovered(
                limit,
                listOf(34 * 60, 40 * 60, 45 * 60),
                listOf(OvertimeTierV2(limit, 43 * 60, 1.25))
            )
        )
    }
}
