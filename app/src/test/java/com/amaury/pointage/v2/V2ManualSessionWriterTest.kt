package com.amaury.pointage.v2

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ManualSessionWriterTest {
    @Test
    fun `session without company keeps an explicit null employer and no legacy slot`() {
        val json = V2ManualSessionWriter.createManualSessionJson(
            id = "manual-test",
            realStartMs = 1_000L,
            realEndMs = 2_000L,
            countedEntryMs = 1_000L,
            countedExitMs = 2_000L,
            employerId = null,
            legacySlot = null,
            placeLabel = "Atelier"
        )

        assertTrue(json.has("employerId"))
        assertTrue(json.isNull("employerId"))
        assertFalse(json.has("companySlot"))
        assertEquals("none", V2ManualSessionWriter.employerKey(null, null))
    }

    @Test
    fun `employer identity distinguishes stable ids legacy slots and no company`() {
        assertEquals("employer:company-a", V2ManualSessionWriter.employerKey("company-a", 1))
        assertEquals("slot:2", V2ManualSessionWriter.employerKey(null, 2))
        assertEquals("none", V2ManualSessionWriter.employerKey(null, null))
    }

    @Test
    fun `explicit null employer never falls back to legacy company one`() {
        val explicitNone = V2RuntimeStore.historyEmployerSource(
            JSONObject().put("employerId", JSONObject.NULL)
        )

        assertNull(explicitNone.employerId)
        assertNull(explicitNone.legacySlot)
        assertFalse(explicitNone.useLegacyProfile)
    }

    @Test
    fun `old history without employer still uses its legacy slot`() {
        val defaultSlot = V2RuntimeStore.historyEmployerSource(JSONObject())
        val secondSlot = V2RuntimeStore.historyEmployerSource(
            JSONObject().put("companySlot", 2)
        )
        val explicitNullWithSlot = V2RuntimeStore.historyEmployerSource(
            JSONObject().put("employerId", JSONObject.NULL).put("companySlot", 2)
        )

        assertTrue(defaultSlot.useLegacyProfile)
        assertEquals(1, defaultSlot.legacySlot)
        assertTrue(secondSlot.useLegacyProfile)
        assertEquals(2, secondSlot.legacySlot)
        assertFalse(explicitNullWithSlot.useLegacyProfile)
        assertNull(explicitNullWithSlot.legacySlot)
        assertNull(explicitNullWithSlot.employerId)
    }
}
