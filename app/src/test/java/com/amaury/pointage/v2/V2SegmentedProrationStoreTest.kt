package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConfirmedProrationSegmentV2
import com.amaury.pointage.v2.engine.ConfirmedSegmentedMonthlyProrationV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

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

    @Test
    fun floatingProrationValuesCannotSaturateLong() {
        listOf<Number>(Long.MAX_VALUE.toDouble(), 1e100, -1e100, Float.MAX_VALUE,
            Double.NaN, Double.POSITIVE_INFINITY, 1.5).forEach {
            assertNull(V2SegmentedProrationStore.strictLong(it))
        }
        assertEquals(Long.MAX_VALUE, V2SegmentedProrationStore.strictLong(Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, V2SegmentedProrationStore.strictLong(Long.MIN_VALUE.toDouble()))
        assertEquals(1234L, V2SegmentedProrationStore.strictLong(1234.0))
    }

    @Test
    fun integralFloatingTimestampRoundTripsFromScientificJson() {
        val value = sample().copy(checkedAtMs = 1790546400000L)
        val payload = JSONObject(V2SegmentedProrationStore.encode(value)!!)
        payload.put("checkedAtMs", value.checkedAtMs.toDouble())
        assertEquals(value, V2SegmentedProrationStore.decode(payload.toString()))
    }

    @Test
    fun arbitraryPrecisionValuesRequireExactLong() {
        assertEquals(Long.MAX_VALUE, V2SegmentedProrationStore.strictLong(java.math.BigInteger.valueOf(Long.MAX_VALUE)))
        assertEquals(1234L, V2SegmentedProrationStore.strictLong(java.math.BigDecimal("1234.0")))
        for (invalid in listOf<Number>(java.math.BigDecimal("1.5"), java.math.BigDecimal("1E100"),
                java.math.BigDecimal("-1E100"), java.math.BigInteger("9223372036854775808"))) {
            assertNull(V2SegmentedProrationStore.strictLong(invalid))
            val payload = JSONObject(V2SegmentedProrationStore.encode(sample())!!)
            payload.put("checkedAtMs", invalid)
            assertNull(V2SegmentedProrationStore.decode(payload.toString()))
        }
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
