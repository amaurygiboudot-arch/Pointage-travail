package com.amaury.pointage

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsLegacyEmployerPromotionV2Test {
    @Test
    fun `stable company id is never replaced by legacy slot`() {
        val zones = JSONArray(
            """[{\"id\":\"zone-a\",\"latitude\":46.7,\"longitude\":-1.4,\"radius\":150,\"companyId\":\"company-c\",\"companySlot\":1}]"""
        )
        val promoted = promoteLegacyGpsEmployerBindingsV2(
            zones, null, listOf("company-a", "company-b", "company-c")
        )
        assertEquals(0, promoted)
        assertEquals("company-c", zones.getJSONObject(0).getString("companyId"))
    }

    @Test
    fun `legacy slot is promoted only when matching confirmed company exists`() {
        val zones = JSONArray(
            """[{\"id\":\"zone-b\",\"latitude\":46.7,\"longitude\":-1.4,\"radius\":150,\"companySlot\":2}]"""
        )
        val promoted = promoteLegacyGpsEmployerBindingsV2(
            zones, null, listOf("company-a", "company-b")
        )
        assertEquals(1, promoted)
        assertEquals("company-b", zones.getJSONObject(0).getString("companyId"))
    }

    @Test
    fun `legacy address binding is case insensitive and missing company stays unpromoted`() {
        val byAddress = JSONArray(
            """[{\"id\":\"zone-a\",\"latitude\":46.7,\"longitude\":-1.4,\"radius\":150,\"address\":\"1 RUE A\"}]"""
        )
        val map = JSONObject().put("1 rue a", 1)
        assertEquals(1, promoteLegacyGpsEmployerBindingsV2(byAddress, map, listOf("company-a")))
        assertEquals("company-a", byAddress.getJSONObject(0).getString("companyId"))

        val missing = JSONArray(
            """[{\"id\":\"zone-c\",\"latitude\":46.7,\"longitude\":-1.4,\"radius\":150,\"companySlot\":2}]"""
        )
        assertEquals(0, promoteLegacyGpsEmployerBindingsV2(missing, null, listOf("company-a")))
        assertFalse(missing.getJSONObject(0).has("companyId"))
    }

    @Test
    fun `geofence reconciliation runs promotion before registration fingerprint`() {
        val source = java.io.File(
            generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
                .first { java.io.File(it, "app/src/main/java/com/amaury/pointage/GeofenceManager.kt").isFile },
            "app/src/main/java/com/amaury/pointage/GeofenceManager.kt"
        ).readText()
        val section = source.substringAfter("private fun runQueuedStoredZonesReconciliation()")
            .substringBefore("private fun addSerializedGeofences")
        assertTrue(section.indexOf("promoteLegacyEmployerBindingsIfPossible") < section.indexOf("storedGpsConfigurationFingerprint"))
    }
}
