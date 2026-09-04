package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnterpriseIdccParserV2Test {
    @Test
    fun readsCurrentAnnuaireResponseAndIgnoresNoConventionMarker() {
        val response = mapOf(
            "complements" to mapOf("liste_idcc" to listOf("2148", "9999")),
            "siege" to mapOf(
                "siret" to "38012986648625",
                "liste_idcc" to listOf("2148", "9999")
            )
        )

        assertEquals(listOf("2148"), EnterpriseIdccParserV2.find(response))
    }

    @Test
    fun prioritizesTheEstablishmentMatchingTheRequestedSiret() {
        val response = mapOf(
            "complements" to mapOf("liste_idcc" to listOf("2148", "1486")),
            "siege" to mapOf("siret" to "11111111111111", "liste_idcc" to listOf("2148")),
            "matching_etablissements" to listOf(
                mapOf("siret" to "22222222222222", "liste_idcc" to listOf("1486", "9999"))
            )
        )

        assertEquals(
            listOf("1486", "2148"),
            EnterpriseIdccParserV2.find(response, "22222222222222")
        )
    }

    @Test
    fun keepsCompatibilityWithOlderIdccShapes() {
        val response = mapOf(
            "idcc_principal" to "IDCC 292",
            "conventions_collectives" to listOf(mapOf("numero_idcc" to "3248"))
        )

        assertEquals(listOf("0292", "3248"), EnterpriseIdccParserV2.find(response))
    }

    @Test
    fun rejectsMalformedOrUnusableIdentifiers() {
        assertNull(EnterpriseIdccParserV2.normalize("12345"))
        assertNull(EnterpriseIdccParserV2.normalize("9999"))
        assertEquals("0275", EnterpriseIdccParserV2.normalize("IDCC 275"))
    }
}
