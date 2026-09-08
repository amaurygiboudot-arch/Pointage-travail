package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialKaliProvidentContributionBandParserV2Test {
    @Test
    fun `deux tranches contigues avec plafond global explicite sont completes`() {
        val result = OfficialKaliProvidentContributionBandParserV2.parse(
            listOf(
                "Cotisations de prévoyance dans la limite de 4 PMSS. " +
                    "Tranche 1 : de 0 à 1 PMSS, part salariale : 0,40 %, part patronale : 0,60 %. " +
                    "Tranche 2 : de 1 à 4 PMSS, part salariale : 0,80 %, part patronale : 1,20 %."
            ).map(OfficialKaliProfileMatcherV2::normalize)
        )

        assertTrue(result.mentioned)
        assertTrue(result.complete)
        assertEquals(2, result.bands.size)
        assertEquals(0.0, result.bands[0].lowerCeilingMultiple, 0.000001)
        assertEquals(1.0, result.bands[0].upperCeilingMultiple!!, 0.000001)
        assertEquals(0.004, result.bands[0].employeeRate, 0.000001)
        assertEquals(0.012, result.bands[1].employerRate, 0.000001)
    }

    @Test
    fun `nom tranche A B sans bornes explicites ne cree jamais de definition implicite`() {
        val result = OfficialKaliProvidentContributionBandParserV2.parse(
            listOf(
                "Tranche A : part salariale 0,40 %, part patronale 0,60 %. Tranche B : part salariale 0,80 %, part patronale 1,20 %."
            ).map(OfficialKaliProfileMatcherV2::normalize)
        )

        assertTrue(result.mentioned)
        assertFalse(result.complete)
        assertTrue(result.bands.isEmpty())
    }

    @Test
    fun `tranches non contigues bloquent le barème`() {
        val result = OfficialKaliProvidentContributionBandParserV2.parse(
            listOf(
                "Cotisation plafonnée à 4 PMSS. " +
                    "Tranche 1 : de 0 à 1 PMSS, salarié 0,40 %, employeur 0,60 %. " +
                    "Tranche 2 : de 2 à 4 PMSS, salarié 0,80 %, employeur 1,20 %."
            ).map(OfficialKaliProfileMatcherV2::normalize)
        )

        assertFalse(result.complete)
        assertTrue(result.reason.orEmpty().contains("contigu"))
    }

    @Test
    fun `borne finale sans plafond global explicite reste incomplete`() {
        val result = OfficialKaliProvidentContributionBandParserV2.parse(
            listOf(
                "Tranche 1 : de 0 à 1 PMSS, salarié 0,40 %, employeur 0,60 %. " +
                    "Tranche 2 : de 1 à 4 PMSS, salarié 0,80 %, employeur 1,20 %."
            ).map(OfficialKaliProfileMatcherV2::normalize)
        )

        assertFalse(result.complete)
        assertTrue(result.reason.orEmpty().contains("plafond global"))
    }

    @Test
    fun `repartition par defaut modifiable dans une tranche reste bloquee`() {
        val result = OfficialKaliProvidentContributionBandParserV2.parse(
            listOf(
                "Cotisation dans la limite de 4 PMSS. " +
                    "Tranche 1 : de 0 à 1 PMSS, par défaut salarié 0,40 %, employeur 0,60 %. " +
                    "Tranche 2 : de 1 à 4 PMSS, salarié 0,80 %, employeur 1,20 %."
            ).map(OfficialKaliProfileMatcherV2::normalize)
        )

        assertFalse(result.complete)
    }
}
