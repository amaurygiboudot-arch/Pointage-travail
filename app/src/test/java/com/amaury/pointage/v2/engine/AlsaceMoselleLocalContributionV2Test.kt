package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlsaceMoselleLocalContributionV2Test {
    @Test
    fun `affiliation confirmee ajoute 1 virgule 30 pourcent du brut`() {
        val withoutLocal = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = 2500.0,
            year = 2026,
            alsaceMoselleLocalRegime = false
        )
        val withLocal = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = 2500.0,
            year = 2026,
            alsaceMoselleLocalRegime = true
        )

        val line = withLocal.lines.firstOrNull { it.id == "alsace_moselle_local_health" }
        assertTrue(line != null)
        assertEquals(2500.0, line!!.baseAmount, 0.001)
        assertEquals(0.013, line.rate, 0.000001)
        assertEquals(32.50, line.employeeAmount, 0.001)
        assertEquals(32.50, withLocal.employeeDeductions - withoutLocal.employeeDeductions, 0.001)
    }

    @Test
    fun `affiliation confirmee non ne cree aucune ligne locale`() {
        val result = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = 2500.0,
            year = 2026,
            alsaceMoselleLocalRegime = false
        )

        assertFalse(result.lines.any { it.id == "alsace_moselle_local_health" })
        assertFalse(result.warnings.any { it.contains("affiliation à confirmer") })
    }

    @Test
    fun `affiliation inconnue ne deduit rien et exige confirmation`() {
        val unknown = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = 2500.0,
            year = 2026,
            alsaceMoselleLocalRegime = null
        )
        val confirmedNo = SocialContributionCatalogV2.estimateEmployeeDeductions(
            gross = 2500.0,
            year = 2026,
            alsaceMoselleLocalRegime = false
        )

        assertFalse(unknown.lines.any { it.id == "alsace_moselle_local_health" })
        assertEquals(confirmedNo.employeeDeductions, unknown.employeeDeductions, 0.001)
        assertTrue(unknown.warnings.any { it.contains("affiliation à confirmer") })
    }
}
