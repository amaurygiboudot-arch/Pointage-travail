package com.amaury.pointage.v2.engine

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConventionPayrollReferenceV2Test {
    private val date = LocalDate.of(2026, 9, 30)

    @Test
    fun `le barème 2026 non étendu ne remplace pas le barème étendu sans confirmation entreprise`() {
        val minimum = ConventionPayrollReferenceV2.minimum("292", date, 800)
        val warnings = ConventionPayrollReferenceV2.minimumApplicabilityWarnings("292", date, 800)

        assertEquals(2226.0, minimum?.monthlyGross ?: 0.0, 0.001)
        assertTrue(warnings.any { it.contains("2266,00") && it.contains("non étendu") })
    }

    @Test
    fun `le barème 2026 devient applicable quand l'applicabilité entreprise est confirmée`() {
        val minimum = ConventionPayrollReferenceV2.minimum("292", date, 800, companyApplicabilityConfirmed = true)
        val warnings = ConventionPayrollReferenceV2.minimumApplicabilityWarnings("292", date, 800, companyApplicabilityConfirmed = true)

        assertEquals(2266.0, minimum?.monthlyGross ?: 0.0, 0.001)
        assertTrue(warnings.isEmpty())
    }
}
