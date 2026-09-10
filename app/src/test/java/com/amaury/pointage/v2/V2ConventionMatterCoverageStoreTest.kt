package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.ConventionClassificationV2
import com.amaury.pointage.v2.engine.ConventionMatterCoverageV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class V2ConventionMatterCoverageStoreTest {
    private val date = LocalDate.of(2026, 9, 1)
    private val classification = ConventionClassificationV2(coefficient = 700)

    private fun record(
        matter: ConventionMatterCoverageV2.Matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
        state: ConventionMatterCoverageV2.State = ConventionMatterCoverageV2.State.CONFIRMED_RULES,
        source: String = "Légifrance KALI",
        checkedAtMs: Long = 1L
    ) = ConventionMatterCoverageV2.Record(
        idcc = "292",
        matter = matter,
        effectiveFrom = date,
        effectiveTo = null,
        classification = classification,
        professionalStatus = "NON_CADRE",
        state = state,
        source = source,
        checkedAtMs = checkedAtMs,
        authorities = setOf(ConventionMatterCoverageV2.Authority.KALI)
    )

    @Test
    fun `historique vide explicite est fiable`() {
        val result = V2ConventionMatterCoverageStore.decodeRecords("[]")

        assertTrue(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json illisible rend historique non fiable`() {
        val result = V2ConventionMatterCoverageStore.decodeRecords("not-json")

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `entree invalide rend historique non fiable`() {
        val result = V2ConventionMatterCoverageStore.decodeRecords("[{}]")

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `deux etats concurrents de la meme identite sont refuses`() {
        val confirmed = record()
        val incomplete = confirmed.copy(
            state = ConventionMatterCoverageV2.State.INCOMPLETE,
            source = "refresh incomplet",
            checkedAtMs = 2L,
            authorities = emptySet()
        )

        assertFalse(V2ConventionMatterCoverageStore.acceptsPackage(listOf(confirmed, incomplete)))
    }

    @Test
    fun `deux matieres distinctes restent valides`() {
        assertTrue(
            V2ConventionMatterCoverageStore.acceptsPackage(
                listOf(
                    record(ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION),
                    record(ConventionMatterCoverageV2.Matter.PROVIDENT_BENEFITS)
                )
            )
        )
    }

    @Test
    fun `stockage corrompu produit toujours une couverture incomplete`() {
        val stored = V2ConventionMatterCoverageStore.ReadResult(
            records = listOf(record()),
            reliable = false,
            warnings = listOf("historique corrompu")
        )

        val result = V2ConventionMatterCoverageStore.resolveStored(
            stored = stored,
            idcc = "292",
            matter = ConventionMatterCoverageV2.Matter.PROVIDENT_CONTRIBUTION,
            date = date,
            classification = classification,
            professionalStatus = "NON_CADRE"
        )

        assertFalse(result.reliable)
        assertTrue(result.state == ConventionMatterCoverageV2.State.INCOMPLETE)
        assertNull(result.record)
        assertTrue(result.warnings.any { it.contains("stockage", ignoreCase = true) })
    }
}
