package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ComplementaryRetirementEmployerV2Test {
    @Test
    fun `tranche 1 uses official 2026 employer shares`() {
        val estimate = ComplementaryRetirementCatalogV2.estimate(
            gross = 3000.0,
            year = 2026,
            professionalStatus = "NON_CADRE"
        )
        val retirement = estimate.lines.first { it.id == "agirc_t1" }
        val ceg = estimate.lines.first { it.id == "ceg_t1" }

        assertEquals(0.0472, retirement.employerRate, 0.000001)
        assertEquals(0.0129, ceg.employerRate, 0.000001)
        assertEquals(3000.0 * (0.0472 + 0.0129), estimate.employerContributions, 0.001)
    }

    @Test
    fun `tranche 2 and CET use official 2026 employer shares`() {
        val estimate = ComplementaryRetirementCatalogV2.estimate(
            gross = 5000.0,
            year = 2026,
            professionalStatus = "NON_CADRE"
        )
        val t2 = estimate.lines.firstOrNull { it.id == "agirc_t2" }
        val cegT2 = estimate.lines.firstOrNull { it.id == "ceg_t2" }
        val cet = estimate.lines.firstOrNull { it.id == "cet" }

        assertNotNull(t2)
        assertNotNull(cegT2)
        assertNotNull(cet)
        assertEquals(0.1295, t2!!.employerRate, 0.000001)
        assertEquals(0.0162, cegT2!!.employerRate, 0.000001)
        assertEquals(0.0021, cet!!.employerRate, 0.000001)
    }

    @Test
    fun `confirmed cadre APEC has employer share`() {
        val estimate = ComplementaryRetirementCatalogV2.estimate(
            gross = 3000.0,
            year = 2026,
            professionalStatus = "CADRE",
            protectionCategory = PlasturgieProtectionCategoryV2.classify(
                idcc = null,
                referenceDate = LocalDate.of(2026, 1, 31),
                coefficient = null
            )
        )
        val apec = estimate.lines.firstOrNull { it.id == "apec" }

        assertNotNull(apec)
        assertEquals(0.00036, apec!!.employerRate, 0.000001)
        assertTrue(apec.employerAmount > 0.0)
    }
}
