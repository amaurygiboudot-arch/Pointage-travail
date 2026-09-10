package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryExamplePdfOfficialSourceReliabilityV2Test {
    @Test
    fun `corrupted LEGI storage is never presented as merely unverified`() {
        val status = SalaryExamplePdfV2.legalSourceStatus(
            reliable = false,
            coveredTopics = 0,
            totalTopics = 7,
            references = emptyList()
        )

        assertTrue(status.summary.contains("incohérent", ignoreCase = true))
        assertTrue(status.references.contains("non fiable", ignoreCase = true))
        assertFalse(status.summary.contains("Non vérifié", ignoreCase = true))
    }

    @Test
    fun `reliable empty LEGI snapshot remains a genuine unverified state`() {
        val status = SalaryExamplePdfV2.legalSourceStatus(
            reliable = true,
            coveredTopics = 0,
            totalTopics = 7,
            references = emptyList()
        )

        assertEquals("Non vérifié pour cette date", status.summary)
        assertEquals("Non vérifié pour la date de paie", status.references)
    }

    @Test
    fun `verified LEGI references keep coverage and bounded reference list`() {
        val status = SalaryExamplePdfV2.legalSourceStatus(
            reliable = true,
            coveredTopics = 4,
            totalTopics = 7,
            references = listOf("L3121-33", "L3121-36", "L3133-6", "R1234-1", "L2253-1", "L2253-3", "L9999-1")
        )

        assertEquals("4/7 thèmes vérifiés", status.summary)
        assertTrue(status.references.contains("L3121-33"))
        assertTrue(status.references.endsWith("+1"))
    }

    @Test
    fun `corrupted BOCC storage is distinct from a reliable empty audit`() {
        val corrupted = SalaryExamplePdfV2.boccSourceStatus(
            configurationIssue = null,
            reliable = false,
            references = emptyList()
        )
        val empty = SalaryExamplePdfV2.boccSourceStatus(
            configurationIssue = null,
            reliable = true,
            references = emptyList()
        )

        assertTrue(corrupted.summary.contains("incohérent", ignoreCase = true))
        assertTrue(corrupted.references.contains("non fiable", ignoreCase = true))
        assertEquals("Non vérifiées pour cette entreprise et cette date", empty.summary)
        assertFalse(empty.references.contains("non fiable", ignoreCase = true))
    }

    @Test
    fun `BOCC without company or IDCC stays a configuration issue`() {
        val status = SalaryExamplePdfV2.boccSourceStatus(
            configurationIssue = "IDCC / entreprise à confirmer",
            reliable = false,
            references = emptyList()
        )

        assertEquals("IDCC / entreprise à confirmer", status.summary)
        assertEquals("IDCC / entreprise à confirmer", status.references)
        assertFalse(status.summary.contains("incohérent", ignoreCase = true))
    }

    @Test
    fun `invalid BOCC IDCC is a configuration error and never a storage corruption`() {
        assertNull(SalaryExamplePdfV2.normalizeBoccIdcc("IDCC 0292"))
        assertNull(SalaryExamplePdfV2.normalizeBoccIdcc("abc"))
        assertNull(SalaryExamplePdfV2.normalizeBoccIdcc("0"))
        assertEquals("292", SalaryExamplePdfV2.normalizeBoccIdcc("0292"))

        val status = SalaryExamplePdfV2.boccSourceStatus(
            configurationIssue = "IDCC invalide — à corriger",
            reliable = false,
            references = emptyList()
        )

        assertEquals("IDCC invalide — à corriger", status.summary)
        assertEquals("IDCC invalide — à corriger", status.references)
        assertFalse(status.summary.contains("stockage", ignoreCase = true))
        assertFalse(status.summary.contains("incohérent", ignoreCase = true))
    }
}
