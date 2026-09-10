package com.amaury.pointage

import com.amaury.pointage.v2.LegalPayrollSourceStoreV2
import com.amaury.pointage.v2.OfficialLegalCodeSourceV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SalaryAdapterLegalSourceReliabilityTest {
    private val allTopics = OfficialLegalCodeSourceV2.Topic.entries.toSet()
    private val referenceAtMs = 1_800_000_000_000L

    private fun record(topic: OfficialLegalCodeSourceV2.Topic, index: Int) =
        LegalPayrollSourceStoreV2.Record(
            topic = topic,
            articleId = "LEGIARTI000000000${index.toString().padStart(2, '0')}",
            articleNumber = "L${1000 + index}",
            status = "VIGUEUR",
            excerpt = "Texte légal vérifié pour le test.",
            effectiveFromMs = 1_700_000_000_000L,
            effectiveToMs = null,
            referenceAtMs = referenceAtMs,
            checkedAtMs = referenceAtMs + index
        )

    @Test
    fun `corrupted LEGI storage keeps its fail closed warning`() {
        val storageWarning =
            "Sources légales LEGI : stockage local incohérent ; aucun article n'est utilisé tant que l'audit officiel n'a pas reconstruit un historique fiable."
        val snapshot = LegalPayrollSourceStoreV2.Snapshot(
            referenceAtMs = referenceAtMs,
            records = emptyList(),
            coveredTopics = emptySet(),
            missingTopics = allTopics,
            reliable = false,
            warnings = listOf(storageWarning)
        )

        val warnings = V2SalaryAdapter.legalPayrollSourceWarnings(snapshot)

        assertEquals(listOf(storageWarning), warnings)
        assertFalse(warnings.any { it.contains("Code du travail non vérifié", ignoreCase = true) })
    }

    @Test
    fun `unreliable LEGI snapshot without warning still fails closed explicitly`() {
        val snapshot = LegalPayrollSourceStoreV2.Snapshot(
            referenceAtMs = referenceAtMs,
            records = emptyList(),
            coveredTopics = emptySet(),
            missingTopics = allTopics,
            reliable = false,
            warnings = emptyList()
        )

        val warnings = V2SalaryAdapter.legalPayrollSourceWarnings(snapshot)

        assertTrue(warnings.single().contains("stockage local incohérent", ignoreCase = true))
        assertFalse(warnings.single().contains("Code du travail non vérifié", ignoreCase = true))
    }

    @Test
    fun `reliable empty LEGI audit remains unverified rather than corrupted`() {
        val snapshot = LegalPayrollSourceStoreV2.Snapshot(
            referenceAtMs = referenceAtMs,
            records = emptyList(),
            coveredTopics = emptySet(),
            missingTopics = allTopics,
            reliable = true,
            warnings = emptyList()
        )

        val warnings = V2SalaryAdapter.legalPayrollSourceWarnings(snapshot)

        assertEquals(
            listOf("Sources légales LEGI : Code du travail non vérifié pour la date de paie."),
            warnings
        )
    }

    @Test
    fun `reliable partial LEGI audit keeps the partial coverage warning`() {
        val topic = OfficialLegalCodeSourceV2.Topic.entries.first()
        val snapshot = LegalPayrollSourceStoreV2.Snapshot(
            referenceAtMs = referenceAtMs,
            records = listOf(record(topic, 1)),
            coveredTopics = setOf(topic),
            missingTopics = allTopics - topic,
            reliable = true,
            warnings = emptyList()
        )

        val warnings = V2SalaryAdapter.legalPayrollSourceWarnings(snapshot)

        assertTrue(warnings.single().contains("contrôle partiel 1/${allTopics.size} thèmes"))
        assertFalse(warnings.single().contains("incohérent", ignoreCase = true))
    }

    @Test
    fun `reliable complete LEGI audit adds no warning`() {
        val records = OfficialLegalCodeSourceV2.Topic.entries.mapIndexed { index, topic ->
            record(topic, index + 1)
        }
        val snapshot = LegalPayrollSourceStoreV2.Snapshot(
            referenceAtMs = referenceAtMs,
            records = records,
            coveredTopics = allTopics,
            missingTopics = emptySet(),
            reliable = true,
            warnings = emptyList()
        )

        assertTrue(V2SalaryAdapter.legalPayrollSourceWarnings(snapshot).isEmpty())
    }
}
