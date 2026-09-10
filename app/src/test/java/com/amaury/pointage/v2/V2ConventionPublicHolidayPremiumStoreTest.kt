package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConventionPublicHolidayPremiumStoreTest {

    @Test
    fun `explicit empty history is reliable`() {
        val result = V2ConventionPublicHolidayPremiumStore.decodeConfirmed("[]")

        assertTrue(result.reliable)
        assertTrue(result.snapshots.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `malformed json is unreliable`() {
        val result = V2ConventionPublicHolidayPremiumStore.decodeConfirmed("not-json")

        assertFalse(result.reliable)
        assertTrue(result.snapshots.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `invalid entry makes the whole history unreliable`() {
        val result = V2ConventionPublicHolidayPremiumStore.decodeConfirmed(
            """[
                ${snapshotJson("V1", 100, 199, 1.5)},
                {"idcc":"0292","versionId":"BROKEN"}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(1, result.snapshots.size)
    }

    @Test
    fun `duplicated version makes history unreliable`() {
        val result = V2ConventionPublicHolidayPremiumStore.decodeConfirmed(
            """[
                ${snapshotJson("V1", 100, 199, 1.5)},
                ${snapshotJson("V1", 200, 299, 1.5)}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(2, result.snapshots.size)
    }

    @Test
    fun `overlapping periods make history unreliable`() {
        val result = V2ConventionPublicHolidayPremiumStore.decodeConfirmed(
            """[
                ${snapshotJson("V1", 100, 220, 1.5)},
                ${snapshotJson("V2", 200, 299, 2.0)}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
    }

    @Test
    fun `valid non overlapping history stays reliable`() {
        val result = V2ConventionPublicHolidayPremiumStore.decodeConfirmed(
            """[
                ${snapshotJson("V1", 100, 199, 1.5)},
                ${snapshotJson("V2", 200, null, 2.0)}
            ]""".trimIndent()
        )

        assertTrue(result.reliable)
        assertEquals(2, result.snapshots.size)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `invalid multiplier makes history unreliable`() {
        val result = V2ConventionPublicHolidayPremiumStore.decodeConfirmed(
            "[${snapshotJson("V1", 100, null, 0.5)}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.snapshots.isEmpty())
    }

    @Test
    fun `negative checked timestamp makes history unreliable`() {
        val result = V2ConventionPublicHolidayPremiumStore.decodeConfirmed(
            "[${snapshotJson("V1", 100, null, 1.5, checkedAtMs = -1)}]"
        )

        assertFalse(result.reliable)
        assertEquals(1, result.snapshots.size)
    }

    private fun snapshotJson(
        versionId: String,
        from: Long,
        to: Long?,
        multiplier: Double,
        checkedAtMs: Long = 1234L
    ): String = """{
        "idcc":"0292",
        "versionId":"$versionId",
        "sourceId":"legifrance:KALI:KALIARTI000000000",
        "effectiveFromEpochDay":$from,
        "effectiveToEpochDay":${to ?: "null"},
        "checkedAtMs":$checkedAtMs,
        "note":"test",
        "multiplier":$multiplier
    }""".trimIndent()
}
