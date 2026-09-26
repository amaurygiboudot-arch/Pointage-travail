package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConfirmedProrationSegmentV2
import com.amaury.pointage.v2.engine.ConfirmedSegmentedMonthlyProrationV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SegmentedProrationStoreTest {
    @Test
    fun confirmedProrationRoundTripsWithoutChangingFacts() {
        val value = sample()
        val raw = V2SegmentedProrationStore.encode(value)

        val decoded = raw?.let(V2SegmentedProrationStore::decode)
        assertEquals(value, decoded)
    }

    @Test
    fun malformedOrDuplicateSegmentsAreRejected() {
        assertNull(V2SegmentedProrationStore.decode("not-json"))
        val duplicate = sample().copy(
            segments = listOf(
                ConfirmedProrationSegmentV2("v1", 0, 14, 2100),
                ConfirmedProrationSegmentV2("v1", 0, 14, 2100)
            )
        )
        assertFalse(V2SegmentedProrationStore.valid(duplicate))
        assertNull(V2SegmentedProrationStore.encode(duplicate))
    }

    @Test
    fun zeroTotalScheduledMinutesCannotProveProration() {
        val zero = sample().copy(
            segments = sample().segments.map { it.copy(scheduledMinutes = 0) }
        )
        assertFalse(V2SegmentedProrationStore.valid(zero))
    }

    @Test
    fun preferenceFileIsManagedByV2Backup() {
        assertTrue(V2BackupManager.isManagedPreferenceFileName(V2SegmentedProrationStore.PREFS))
    }

    private fun sample() = ConfirmedSegmentedMonthlyProrationV2(
        sourceId = "planning-confirmed",
        checkedAtMs = 1234L,
        segments = listOf(
            ConfirmedProrationSegmentV2("v1", 0, 14, 2100),
            ConfirmedProrationSegmentV2("v2", 15, 30, 2100)
        )
    )
}
