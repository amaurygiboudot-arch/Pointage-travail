package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfficialConventionContainerParserV2Test {
    private val response = mapOf(
        "id" to "KALICONT000005635856",
        "nature" to "IDCC",
        "num" to "292",
        "numeroTexte" to "IDCC 292",
        "titre" to "Convention collective nationale de la plasturgie",
        "texteBaseId" to listOf("KALITEXT000005680736")
    )

    @Test
    fun verifiesExactIdccAndKeepsOfficialIdentifiers() {
        val result = OfficialConventionContainerParserV2.parseVerified(response, "0292", 1234L)

        requireNotNull(result)
        assertEquals("0292", result.idcc)
        assertEquals("KALICONT000005635856", result.containerId)
        assertEquals(listOf("KALITEXT000005680736"), result.baseTextIds)
        assertEquals(1234L, result.checkedAtMs)
    }

    @Test
    fun rejectsAnotherConvention() {
        assertNull(OfficialConventionContainerParserV2.parseVerified(response, "3248"))
    }

    @Test
    fun acceptsAContainerNestedByCallableSerialization() {
        val result = OfficialConventionContainerParserV2.parseVerified(
            mapOf("result" to mapOf("kaliCont" to response)),
            "292"
        )

        assertEquals("0292", result?.idcc)
    }

    @Test
    fun acceptsASingleBaseTextIdentifier() {
        val result = OfficialConventionContainerParserV2.parseVerified(
            response + ("texteBaseId" to "KALITEXT000005680736"),
            "0292"
        )

        assertEquals(listOf("KALITEXT000005680736"), result?.baseTextIds)
    }

    @Test
    fun buildsOnlySafeOfficialUrls() {
        assertEquals(
            "https://www.legifrance.gouv.fr/conv_coll/id/KALICONT000005635856",
            OfficialConventionContainerParserV2.publicUrl("KALICONT000005635856")
        )
        assertNull(OfficialConventionContainerParserV2.publicUrl("https://example.com"))
    }
}
