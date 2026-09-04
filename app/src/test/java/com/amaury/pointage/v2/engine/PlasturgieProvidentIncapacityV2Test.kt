package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlasturgieProvidentIncapacityV2Test {
    @Test
    fun `moins de trois mois n ouvre pas la garantie de branche`() {
        val result = PlasturgieProvidentIncapacityV2.assess("0292", 2, "NON_CADRE")
        assertTrue(result.applicableConvention)
        assertFalse(result.potentiallyCovered)
        assertTrue(result.eligibilityConfirmed)
        assertNull(result.minimumGrossRate)
    }

    @Test
    fun `entre trois mois et un an le relais est indique au 91e jour`() {
        val result = PlasturgieProvidentIncapacityV2.assess("292", 8, "NON_CADRE")
        assertTrue(result.potentiallyCovered)
        assertFalse(result.eligibilityConfirmed)
        assertEquals(0.60, result.minimumGrossRate!!, 0.001)
        assertEquals(91, result.earliestContinuousStopDay)
        assertTrue(result.relayAfterEmployerMaintenance)
    }

    @Test
    fun `a partir d un an le relais reste apres le maintien sans inventer de jour`() {
        val result = PlasturgieProvidentIncapacityV2.assess("292", 48, "NON_CADRE")
        assertTrue(result.potentiallyCovered)
        assertNull(result.earliestContinuousStopDay)
        assertTrue(result.relayAfterEmployerMaintenance)
        assertFalse(result.exactBenefitAmountAvailable)
    }

    @Test
    fun `statut cadre ne reutilise pas automatiquement le minimum non cadre`() {
        val result = PlasturgieProvidentIncapacityV2.assess("292", 48, "CADRE")
        assertFalse(result.potentiallyCovered)
        assertNull(result.minimumGrossRate)
        assertTrue(result.warnings.any { it.contains("articles 2.1/2.2") })
    }

    @Test
    fun `autre convention ne declenche pas le relais plasturgie`() {
        val result = PlasturgieProvidentIncapacityV2.assess("1486", 48, "NON_CADRE")
        assertFalse(result.applicableConvention)
        assertFalse(result.potentiallyCovered)
    }
}
