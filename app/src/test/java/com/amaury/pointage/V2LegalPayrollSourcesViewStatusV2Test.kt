package com.amaury.pointage

import com.amaury.pointage.v2.LegalPayrollSourceStoreV2
import com.amaury.pointage.v2.OfficialLegalCodeSourceV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2LegalPayrollSourcesViewStatusV2Test {
    private val allTopics = OfficialLegalCodeSourceV2.Topic.entries.toSet()

    @Test
    fun `corrupted LEGI storage is displayed as corruption and never as empty audit`() {
        val snapshot = LegalPayrollSourceStoreV2.Snapshot(
            referenceAtMs = 1_800_000_000_000L,
            records = emptyList(),
            coveredTopics = emptySet(),
            missingTopics = allTopics,
            reliable = false,
            warnings = listOf("Sources légales LEGI : stockage local incohérent.")
        )

        val text = legalPayrollSourceStatusText("31/01/2026", snapshot)

        assertTrue(text.contains("Stockage LEGI local incohérent", ignoreCase = true))
        assertTrue(text.contains("stockage local incohérent", ignoreCase = true))
        assertFalse(text.contains("Aucune source LEGI vérifiée", ignoreCase = true))
    }

    @Test
    fun `reliable empty LEGI storage remains a genuine empty audit`() {
        val snapshot = LegalPayrollSourceStoreV2.Snapshot(
            referenceAtMs = 1_800_000_000_000L,
            records = emptyList(),
            coveredTopics = emptySet(),
            missingTopics = allTopics,
            reliable = true,
            warnings = emptyList()
        )

        val text = legalPayrollSourceStatusText("31/01/2026", snapshot)

        assertTrue(text.contains("Aucune source LEGI vérifiée pour cette date."))
        assertFalse(text.contains("incohérent", ignoreCase = true))
    }
}
