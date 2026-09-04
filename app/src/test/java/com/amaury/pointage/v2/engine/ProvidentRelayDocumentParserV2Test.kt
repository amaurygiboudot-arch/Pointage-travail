package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidentRelayDocumentParserV2Test {
    @Test
    fun `extrait les trois montants d un decompte explicite`() {
        val result = ProvidentRelayDocumentParserV2.parse(
            """
            Décompte incapacité
            60 % du salaire brut de référence : 1 800,00 €
            Prestations Sécurité sociale brutes déduites : 620,50 €
            Prestation prévoyance brute versée : 1 179,50 €
            """.trimIndent()
        )

        assertTrue(result.allHighConfidence)
        assertEquals(1800.0, result.targetGross60.amount!!, 0.001)
        assertEquals(620.50, result.socialSecurityGross.amount!!, 0.001)
        assertEquals(1179.50, result.observedProvidentGross.amount!!, 0.001)
    }

    @Test
    fun `supporte un montant place sur la ligne suivante`() {
        val result = ProvidentRelayDocumentParserV2.parse(
            """
            Garantie 60 % salaire brut de référence
            1 500,00 €
            IJSS brutes
            500,00 €
            Prestation prévoyance brute versée
            1 000,00 €
            """.trimIndent()
        )

        assertTrue(result.targetGross60.highConfidence)
        assertTrue(result.socialSecurityGross.highConfidence)
        assertTrue(result.observedProvidentGross.highConfidence)
    }

    @Test
    fun `ne confond pas le minimum 60 pourcent avec la prestation observee`() {
        val result = ProvidentRelayDocumentParserV2.parse(
            """
            Garantie prévoyance minimum 60 % du salaire brut : 1 800,00 €
            IJSS brutes : 600,00 €
            """.trimIndent()
        )

        assertNull(result.observedProvidentGross.amount)
        assertFalse(result.allHighConfidence)
    }

    @Test
    fun `une ambiguite entre deux montants proches en confiance bloque le pre remplissage`() {
        val result = ProvidentRelayDocumentParserV2.parse(
            """
            Prestations Sécurité sociale brutes : 500,00 €
            IJSS brutes déduites : 600,00 €
            """.trimIndent()
        )

        assertNull(result.socialSecurityGross.amount)
        assertFalse(result.socialSecurityGross.highConfidence)
    }
}
