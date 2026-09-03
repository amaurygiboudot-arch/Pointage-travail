package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialAgreementSearchParserV2Test {
    @Test
    fun readsAccordFromOfficialNestedTitlesShape() {
        val response = mapOf(
            "totalResultNumber" to 1,
            "results" to listOf(
                mapOf(
                    "titles" to listOf(
                        mapOf(
                            "id" to "ACCOTEXT000053766350",
                            "cid" to "ACCO000053766350",
                            "title" to "Accord salarial 2026 Orange SA"
                        )
                    ),
                    "raisonSociale" to "ORANGE",
                    "dateSignature" to "2026-03-13"
                )
            )
        )

        val agreements = OfficialAgreementSearchParserV2.parseCandidates(response)

        assertEquals(1, agreements.size)
        assertEquals("ACCOTEXT000053766350", agreements.single().id)
        assertEquals("Accord salarial 2026 Orange SA", agreements.single().title)
        assertEquals("2026-03-13", agreements.single().effectiveFrom)
    }

    @Test
    fun keepsLegacyTopLevelSearchShapeCompatible() {
        val response = mapOf(
            "results" to listOf(
                mapOf(
                    "id" to "ACCOTEXT000051437142",
                    "titre" to "Accord GEPP Orange 2025-2027",
                    "dateSignature" to "2025-02-10"
                )
            )
        )

        val agreements = OfficialAgreementSearchParserV2.parseCandidates(response)

        assertEquals("ACCOTEXT000051437142", agreements.single().id)
        assertEquals("Accord GEPP Orange 2025-2027", agreements.single().title)
    }

    @Test
    fun rejectsNestedTitlesWithoutAnAccoTextIdentifier() {
        val response = mapOf(
            "results" to listOf(
                mapOf(
                    "titles" to listOf(
                        mapOf(
                            "id" to "LEGITEXT000000000000",
                            "title" to "Autre fonds"
                        )
                    )
                )
            )
        )

        assertTrue(OfficialAgreementSearchParserV2.parseCandidates(response).isEmpty())
    }
}
