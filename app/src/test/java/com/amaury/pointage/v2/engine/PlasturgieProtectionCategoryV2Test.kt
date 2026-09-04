package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlasturgieProtectionCategoryV2Test {
    private val date = LocalDate.of(2026, 9, 1)

    @Test fun `900 a 940 relevent de ANI 2 1`() {
        listOf(900, 920, 940).forEach { coefficient ->
            val result = PlasturgieProtectionCategoryV2.classify("0292", date, coefficient)
            assertTrue(result.confirmed)
            assertEquals(PlasturgieProtectionCategoryV2.Category.ARTICLE_2_1, result.category)
        }
    }

    @Test fun `830 releve de ANI 2 2`() {
        val result = PlasturgieProtectionCategoryV2.classify("292", date, 830)
        assertTrue(result.confirmed)
        assertEquals(PlasturgieProtectionCategoryV2.Category.ARTICLE_2_2, result.category)
    }

    @Test fun `800 a 820 restent hors ANI mais extension possible`() {
        listOf(800, 810, 820).forEach { coefficient ->
            val result = PlasturgieProtectionCategoryV2.classify("292", date, coefficient)
            assertEquals(PlasturgieProtectionCategoryV2.Category.EXTENSION_ELIGIBLE, result.category)
            assertTrue(result.warnings.any { it.contains("extension") })
        }
    }

    @Test fun `700 reste hors ANI 2 1 et 2 2`() {
        val result = PlasturgieProtectionCategoryV2.classify("292", date, 700)
        assertEquals(PlasturgieProtectionCategoryV2.Category.OUTSIDE_2_1_2_2, result.category)
    }

    @Test fun `coefficient manquant bloque la conclusion`() {
        val result = PlasturgieProtectionCategoryV2.classify("292", date, null)
        assertFalse(result.confirmed)
        assertEquals(PlasturgieProtectionCategoryV2.Category.TO_CONFIRM, result.category)
    }

    @Test fun `ne retro applique pas le classement avant 2025`() {
        val result = PlasturgieProtectionCategoryV2.classify("292", LocalDate.of(2024, 12, 31), 830)
        assertFalse(result.confirmed)
        assertEquals(PlasturgieProtectionCategoryV2.Category.TO_CONFIRM, result.category)
    }
}
