package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class OfficialKaliTextIdentityV2Test {
    @Test
    fun `titre Accord du avec date explicite fournit une identité cross-source fiable`() {
        val data = mapOf(
            "id" to "KALITEXT000000000001",
            "titre" to "Accord du 27 juin 2024 relatif aux catégories de bénéficiaire du régime de protection sociale complémentaire"
        )

        val result = OfficialKaliTextIdentityV2.parse(data, "KALITEXT000000000001")

        assertNotNull(result.identity)
        assertEquals(LocalDate.of(2024, 6, 27), result.identity!!.agreementDate)
        assertTrue(result.identity!!.reliableForCrossSourceScope)
    }

    @Test
    fun `dateSignature explicite est acceptée sans utiliser dateDebut`() {
        val data = mapOf(
            "id" to "KALITEXT000000000001",
            "titre" to "Accord relatif aux catégories objectives",
            "dateSignature" to "2024-06-27",
            "dateDebut" to "2025-01-01"
        )

        val result = OfficialKaliTextIdentityV2.parse(data, "KALITEXT000000000001")

        assertEquals(LocalDate.of(2024, 6, 27), result.identity?.agreementDate)
        assertTrue(result.identity?.reliableForCrossSourceScope == true)
    }

    @Test
    fun `dateDebut seule ne devient jamais date accord`() {
        val data = mapOf(
            "id" to "KALITEXT000000000001",
            "titre" to "Dispositions relatives aux catégories objectives",
            "dateDebut" to "2025-01-01"
        )

        val result = OfficialKaliTextIdentityV2.parse(data, "KALITEXT000000000001")

        assertNotNull(result.identity)
        assertNull(result.identity!!.agreementDate)
        assertFalse(result.identity!!.reliableForCrossSourceScope)
    }

    @Test
    fun `dates signature et titre contradictoires bloquent identité fiable`() {
        val data = mapOf(
            "id" to "KALITEXT000000000001",
            "titre" to "Accord du 27 juin 2024 relatif aux catégories objectives",
            "dateSignature" to "2024-06-28"
        )

        val result = OfficialKaliTextIdentityV2.parse(data, "KALITEXT000000000001")

        assertNull(result.identity?.agreementDate)
        assertFalse(result.identity?.reliableForCrossSourceScope == true)
        assertTrue(result.reasons.any { it.contains("plusieurs dates") })
    }

    @Test
    fun `réponse pour un autre KALITEXT est refusée`() {
        val result = OfficialKaliTextIdentityV2.parse(
            mapOf("id" to "KALITEXT000000000002", "titre" to "Accord du 27 juin 2024"),
            "KALITEXT000000000001"
        )

        assertNull(result.identity)
    }
}
