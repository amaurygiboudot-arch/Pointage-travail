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
    fun adjacentTiersCoverWithoutDoubleCountingRequirement() {
        assertTrue(
            OvertimeCoverageV2.isFullyCovered(
                limit,
                43 * 60,
                listOf(
                    OvertimeTierV2(limit, 43 * 60, 1.25),
                    OvertimeTierV2(43 * 60, null, 1.50)
                )
            )
        )
    }
}
