package com.amaury.pointage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyPauseOverlapV2Test {
    @Test
    fun `reject overlapping daytime pauses but accept a shared boundary`() {
        assertTrue(CompanyPauseOverlapV2.overlaps(600, 660, 650, 690))
        assertFalse(CompanyPauseOverlapV2.overlaps(600, 660, 660, 690))
    }

    @Test
    fun `detect overlap across midnight on either side`() {
        assertTrue(CompanyPauseOverlapV2.overlaps(1380, 60, 30, 90))
        assertTrue(CompanyPauseOverlapV2.overlaps(30, 90, 1380, 60))
        assertFalse(CompanyPauseOverlapV2.overlaps(1380, 60, 60, 120))
        assertFalse(CompanyPauseOverlapV2.overlaps(1380, 0, 0, 60))
    }
}
