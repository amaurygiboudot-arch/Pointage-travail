package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialConventionCatalogParserV2Test {
    @Test
    fun readsOfficialConventionListShape() {
        val page = OfficialConventionCatalogParserV2.parse(
            mapOf(
                "totalResultNumber" to 1,
                "results" to listOf(
                    mapOf(
                        "id" to "KALITEXT000005680736",
                        "cidConteneur" to "KALICONT000005635856",
                        "idcc" to "292",
                        "titre" to "Convention collective nationale de la plasturgie"
                    )
                )
            )
        )

        assertEquals(1, page.totalResultNumber)
        assertEquals(1, page.rawResultCount)
        assertEquals("0292", page.items.single().idcc)
        assertEquals("KALITEXT000005680736", page.items.single().textId)
        assertEquals("Convention collective nationale de la plasturgie", page.items.single().title)
    }

    @Test
    fun rejectsMissingOrInvalidIdcc() {
        assertNull(OfficialConventionCatalogParserV2.normalizeIdcc("IDCC inconnu"))
        assertNull(OfficialConventionCatalogParserV2.normalizeIdcc("12345"))
        assertEquals("0292", OfficialConventionCatalogParserV2.normalizeIdcc("IDCC 292"))
    }
}
