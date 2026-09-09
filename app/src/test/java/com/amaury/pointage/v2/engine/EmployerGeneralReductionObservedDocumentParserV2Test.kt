package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployerGeneralReductionObservedDocumentParserV2Test {
    @Test
    fun `rgdu explicite est proposée avec forte confiance`() {
        val result = EmployerGeneralReductionObservedDocumentParserV2.parse(
            """
            Bulletin de paie
            Réduction générale dégressive unique RGDU 428,37 €
            Net à payer 1 942,10 €
            """.trimIndent()
        )

        assertTrue(result.rgdu.highConfidence)
        assertEquals(428.37, result.rgdu.amount!!, 0.001)
        assertTrue(result.rgdu.sourceLabel!!.contains("RGDU"))
    }

    @Test
    fun `libelle reduction generale cotisations est reconnu`() {
        val result = EmployerGeneralReductionObservedDocumentParserV2.parse(
            "Réduction générale des cotisations patronales 312,54 €"
        )

        assertTrue(result.rgdu.highConfidence)
        assertEquals(312.54, result.rgdu.amount!!, 0.001)
    }

    @Test
    fun `un total global des exonerations nest jamais pris pour la rgdu`() {
        val result = EmployerGeneralReductionObservedDocumentParserV2.parse(
            "Total des exonérations patronales 640,00 €"
        )

        assertNull(result.rgdu.amount)
        assertFalse(result.rgdu.highConfidence)
    }

    @Test
    fun `deux montants rgdu concurrents bloquent le pre remplissage`() {
        val result = EmployerGeneralReductionObservedDocumentParserV2.parse(
            """
            RGDU 410,20 €
            RGDU rectifiée 425,80 €
            """.trimIndent()
        )

        assertNull(result.rgdu.amount)
        assertFalse(result.rgdu.highConfidence)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `le dernier montant monetaire de la ligne explicite est retenu`() {
        val result = EmployerGeneralReductionObservedDocumentParserV2.parse(
            "RGDU base 2 050,00 taux 0,2100 montant 430,50 €"
        )

        assertTrue(result.rgdu.highConfidence)
        assertEquals(430.50, result.rgdu.amount!!, 0.001)
    }

    @Test
    fun `un texte sans rgdu exige une confirmation manuelle`() {
        val result = EmployerGeneralReductionObservedDocumentParserV2.parse(
            "Cotisations employeur 820,00 €\nAllègements employeur 310,00 €"
        )

        assertNull(result.rgdu.amount)
        assertFalse(result.rgdu.highConfidence)
        assertTrue(result.warnings.any { it.contains("confirmation manuelle", ignoreCase = true) })
    }
}
