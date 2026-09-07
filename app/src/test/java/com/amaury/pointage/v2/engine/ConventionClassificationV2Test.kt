package com.amaury.pointage.v2.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConventionClassificationV2Test {
    @Test
    fun `classification supports non numeric french grids`() {
        val actual = ConventionClassificationV2(
            level = " III ",
            echelon = "2",
            group = "B",
            employment = "Technicien atelier"
        )

        assertTrue(actual.matches(ConventionClassificationV2(level = "iii", echelon = "2")))
        assertTrue(actual.matches(ConventionClassificationV2(group = "b")))
        assertFalse(actual.matches(ConventionClassificationV2(level = "IV")))
    }

    @Test
    fun `selector never guesses missing classification`() {
        val actual = ConventionClassificationV2(coefficient = 800)

        assertFalse(actual.matches(ConventionClassificationV2(level = "D4")))
        assertTrue(actual.matches(ConventionClassificationV2(coefficient = 800)))
    }
}
