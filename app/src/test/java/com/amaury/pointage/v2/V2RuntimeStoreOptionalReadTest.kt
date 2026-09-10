package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2RuntimeStoreOptionalReadTest {
    @Test
    fun `champ temporel optionnel absent reste valide et inconnu`() {
        val result = V2RuntimeStore.optionalStrictPositive(emptyMap(), "optional_time")

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
