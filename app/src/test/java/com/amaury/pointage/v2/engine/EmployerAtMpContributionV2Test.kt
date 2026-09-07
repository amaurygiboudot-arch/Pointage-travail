package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployerAtMpContributionV2Test {
    @Test
    fun `taux confirme calcule uniquement la part employeur`() {
        val result = EmployerAtMpContributionV2.calculate(2500.0, 0.0208)

        assertTrue(result.complete)
        assertEquals(0.0208, result.rate!!, 0.000001)
        assertEquals(52.0, result.employerAmount!!, 0.001)
    }

    @Test
    fun `taux inconnu ne fabrique aucun montant`() {
        val result = EmployerAtMpContributionV2.calculate(2500.0, null)

        assertFalse(result.complete)
        assertNull(result.employerAmount)
        assertTrue(result.warnings.any { it.contains("coût employeur incomplet") })
    }
}
