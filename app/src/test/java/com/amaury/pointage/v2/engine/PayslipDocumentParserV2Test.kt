package com.amaury.pointage.v2.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PayslipDocumentParserV2Test {
    @Test
    fun `extrait les totaux explicites et les lignes variables`() {
        val result = PayslipDocumentParserV2.parse(
            """
            Bulletin de paie
            Heures supplémentaires 25 % 8,00 17,13 137,04
            Prime ancienneté 52,44 €
            Panier équipe 10,00 5,38 53,80 €
            Total brut 2 350,40 €
            Net imposable 1 900,12 €
            Net à payer avant impôt sur le revenu 1 845,32 €
            Mutuelle part salariale 28,40 €
            Prévoyance part salarié 12,60 €
            Agirc-Arrco tranche 1 part salariale 86,20 €
            CEG part salarié 22,40 €
            """.trimIndent()
        )

        assertTrue(result.gross.highConfidence)
        assertEquals(2350.40, result.gross.amount!!, 0.001)
        assertEquals(1845.32, result.netBeforeTax.amount!!, 0.001)
        assertEquals(1900.12, result.netTaxable.amount!!, 0.001)
        assertEquals(137.04, result.overtimeGross.amount!!, 0.001)
        assertEquals(52.44, result.premiumsGross.amount!!, 0.001)
        assertEquals(53.80, result.mealBaskets.amount!!, 0.001)
        assertEquals(28.40, result.mutualEmployee.amount!!, 0.001)
        assertEquals(12.60, result.providentEmployee.amount!!, 0.001)
        assertEquals(108.60, result.complementaryRetirementEmployee.amount!!, 0.001)
        assertTrue(result.complementaryRetirementEmployee.highConfidence)
    }

    @Test
    fun `additionne plusieurs lignes heures sup sans les compter en primes`() {
        val result = PayslipDocumentParserV2.parse(
            """
            Heures supplémentaires 25 % 4,00 17,13 68,52 €
            Heures supplémentaires 50 % 2,00 20,55 41,10 €
            Majoration nuit 24,80 €
            Total brut 2 000,00 €
            """.trimIndent()
        )

        assertEquals(109.62, result.overtimeGross.amount!!, 0.001)
        assertEquals(24.80, result.premiumsGross.amount!!, 0.001)
    }

    @Test
    fun `ne devine pas une part salariale quand mutuelle prevoyance et retraite sont ambigues`() {
        val result = PayslipDocumentParserV2.parse(
            """
            Mutuelle 3428,00 1,00 34,28 51,42
            Prévoyance 3428,00 0,50 17,14 25,71
            Agirc-Arrco tranche 1 3428,00 3,15 107,98 161,97
            CEG 3428,00 0,86 29,48 44,22
            Total brut 3 428,00 €
            """.trimIndent()
        )

        assertNull(result.mutualEmployee.amount)
        assertNull(result.providentEmployee.amount)
        assertNull(result.complementaryRetirementEmployee.amount)
        assertFalse(result.mutualEmployee.highConfidence)
        assertFalse(result.providentEmployee.highConfidence)
        assertFalse(result.complementaryRetirementEmployee.highConfidence)
    }

    @Test
    fun `une ambiguite entre deux totaux bruts bloque le pre remplissage`() {
        val result = PayslipDocumentParserV2.parse(
            """
            Total brut 2 000,00 €
            Total brut rectifié 2 100,00 €
            """.trimIndent()
        )

        assertNull(result.gross.amount)
        assertFalse(result.gross.highConfidence)
    }
    @Test
    fun `un cumul annuel ne remplace pas le montant du mois`() {
        val result = PayslipDocumentParserV2.parse("Total brut annuel 28 000,00 €\nTotal brut 2 000,00 €\nNet imposable cumulé 21 000,00 €\nPrime annuelle cumul 1 500,00 €")
        assertEquals(2000.0, result.gross.amount!!, 0.001)
        assertNull(result.netTaxable.amount)
        assertNull(result.premiumsGross.amount)
    }

    @Test
    fun `une correction negative ne devient pas le taux positif qui la precede`() {
        val result = PayslipDocumentParserV2.parse("Heures supplémentaires 25 % 8,00 17,13 -137,04 €")
        assertNull(result.overtimeGross.amount)
        assertFalse(result.overtimeGross.highConfidence)
    }
    @Test
    fun `les colonnes mensuelles annuelles et patronales ne sont pas devinees`() {
        val result = PayslipDocumentParserV2.parse("Total brut 2 000,00 24 000,00\nMutuelle part salariale 28,40 42,60\nAgirc-Arrco part salariale 86,20 129,30")
        assertNull(result.gross.amount)
        assertNull(result.mutualEmployee.amount)
        assertNull(result.complementaryRetirementEmployee.amount)
    }

    @Test
    fun `une prime annuelle du mois reste observable et une correction bloque le total partiel`() {
        val result = PayslipDocumentParserV2.parse("Prime annuelle 1 500,00 €\nHeures supplémentaires 25 % 8,00 17,13 137,04 €\nHeures supplémentaires 25 % 1,00 17,13 -17,13 €")
        assertEquals(1500.0, result.premiumsGross.amount!!, 0.001)
        assertNull(result.overtimeGross.amount)
    }
    @Test
    fun `le taux et le salaire de base ne deviennent pas le brut total`() {
        val result = PayslipDocumentParserV2.parse("Salaire brut horaire 13,63 €\nSalaire brut de base 2 000,00 €")
        assertNull(result.gross.amount)
    }
    @Test
    fun `les signes negatifs OCR et parentheses comptables ne deviennent pas positifs`() {
        listOf("Total brut −100,00 €", "Total brut (100,00)", "Prime correction −100,00 €", "Total brut - 100,00", "Total brut –100,00", "Total brut —100,00").forEach { text ->
            val result = PayslipDocumentParserV2.parse(text)
            assertNull(result.gross.amount)
            assertNull(result.premiumsGross.amount)
        }
    }
    @Test
    fun `les espaces fines insecables conservent les milliers du bulletin`() {
        val result = PayslipDocumentParserV2.parse("Total brut 1\u202f234,56 €\nPrime annuelle 1\u202f500,00 €\nNet imposable 1\u202f010,20 €")
        assertEquals(1234.56, result.gross.amount!!, 0.001)
        assertEquals(1500.0, result.premiumsGross.amount!!, 0.001)
        assertEquals(1010.20, result.netTaxable.amount!!, 0.001)
    }
}
