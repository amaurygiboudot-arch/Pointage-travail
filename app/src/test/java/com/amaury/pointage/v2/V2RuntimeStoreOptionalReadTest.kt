package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RuntimeStoreOptionalReadTest {
    @Test fun `missing entry with residual current facts is corruption rather than empty runtime`() {
        listOf("session_id", "employer_id", "company_slot", "counted_entry", "real_exit",
            "counted_exit", "expected_end", "time_basis", "pause_start", "pause_source",
            "pause_paid", "pauses", "place_id", "place_label").forEach { key ->
            assertTrue(key, V2RuntimeStore.hasOrphanedCurrentSession(mapOf(key to null)))
        }
        assertFalse(V2RuntimeStore.hasOrphanedCurrentSession(emptyMap<String, Any>()))
        assertFalse(V2RuntimeStore.hasOrphanedCurrentSession(mapOf("history" to "[]")))
        assertFalse(V2RuntimeStore.hasOrphanedCurrentSession(mapOf("real_entry" to 123L, "session_id" to "id")))
    }

    @Test
    fun `champ temporel optionnel absent reste valide et inconnu`() {
        val result = V2RuntimeStore.optionalStrictPositive(emptyMap<String, Any?>(), "optional_time")

        assertTrue(result.valid)
        assertNull(result.value)
    }

    @Test
    fun `champ temporel optionnel present et valide est conserve`() {
        val result = V2RuntimeStore.optionalStrictPositive(
            mapOf("optional_time" to 12_345L),
            "optional_time"
        )

        assertTrue(result.valid)
        assertTrue(result.value == 12_345L)
    }

    @Test
    fun `champ temporel optionnel present mais invalide est refuse`() {
        val invalidValues = listOf<Any?>(0L, -1L, "not-a-time", 12.5, Double.NaN, null)

        invalidValues.forEach { invalid ->
            val result = V2RuntimeStore.optionalStrictPositive(
                mapOf("optional_time" to invalid),
                "optional_time"
            )

            assertFalse("La valeur $invalid ne doit pas être considérée fiable", result.valid)
            assertNull(result.value)
        }
    }
}
