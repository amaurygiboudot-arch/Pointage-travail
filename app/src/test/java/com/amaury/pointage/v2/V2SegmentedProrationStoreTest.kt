package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConfirmedProrationSegmentV2
import com.amaury.pointage.v2.engine.ConfirmedSegmentedMonthlyProrationV2
import org.junit.Assert.*
import org.junit.Test

class V2SegmentedProrationStoreTest {
    @Test fun validPayloadRoundTrips() {
        val value = proration()
        val raw = V2SegmentedProrationStore.encode(value)
        assertNotNull(raw)
        assertEquals(value, V2SegmentedProrationStore.decode(raw!!))
    }

    @Test fun missingOrCorruptPayloadNeverBecomesZeroProration() {
        assertNull(V2SegmentedProrationStore.decode(""))
        assertNull(V2SegmentedProrationStore.decode("{}"))
        assertNull(V2SegmentedProrationStore.decode("{\"sourceId\":\"x\",\"checkedAtMs\":1,\"method\":\"SCHEDULED_MINUTES\",\"segments\":[]}"))
    }

    @Test fun duplicateSegmentIdentityIsRejected() {
        val segment = ConfirmedProrationSegmentV2("v1", 0, 14, 100)
        assertNull(V2SegmentedProrationStore.encode(
            ConfirmedSegmentedMonthlyProrationV2(
                sourceId = "confirmed",
                checkedAtMs = 1,
                segments = listOf(segment, segment)
            )
        ))
    }

    @Test fun zeroTotalAndInvertedBoundsAreRejected() {
        assertFalse(V2SegmentedProrationStore.valid(
            ConfirmedSegmentedMonthlyProrationV2(
                sourceId = "confirmed",
                checkedAtMs = 1,
                segments = listOf(
                    ConfirmedProrationSegmentV2("v1", 0, 14, 0),
                    ConfirmedProrationSegmentV2("v2", 15, 30, 0)
                )
            )
        ))
        assertFalse(V2SegmentedProrationStore.valid(
            ConfirmedSegmentedMonthlyProrationV2(
                sourceId = "confirmed",
                checkedAtMs = 1,
                segments = listOf(ConfirmedProrationSegmentV2("v1", 14, 0, 100))
            )
        ))
    }

    @Test fun storageKeyUsesStableCompanyAndMonth() {
        assertEquals("company.2026-09", V2SegmentedProrationStore.storageKey(" company ", 2026, 8))
        assertNull(V2SegmentedProrationStore.storageKey("", 2026, 8))
        assertNull(V2SegmentedProrationStore.storageKey("company", 2026, 12))
    }

    private fun proration() = ConfirmedSegmentedMonthlyProrationV2(
        sourceId = "planning-confirmed",
        checkedAtMs = 42,
        segments = listOf(
            ConfirmedProrationSegmentV2("v1", 0, 14, 4_200),
            ConfirmedProrationSegmentV2("v2", 15, 30, 4_200)
        )
    )
}
