package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class PayslipProfileDraftParserV2Test {
    @Test fun `explicit rate retains source without using gross totals`() {
        val draft = PayslipProfileDraftParserV2.parse("Total brut 2400,00\nTaux horaire brut : 13,632 €")
        assertEquals(13.632, draft.hourlyRate!!, 0.0001)
        assertEquals("Taux horaire brut : 13,632 €", draft.sourceLine)
    }
    @Test fun `conflicting rates and multi column rows remain unknown`() {
        listOf("Taux horaire brut 13,63\nTaux horaire brut 14,00", "Taux horaire brut 13,63 2400,00", "Salaire de base 151,67 13,63 2067,26", "Total brut 2400,00").forEach {
            assertNull(it, PayslipProfileDraftParserV2.parse(it).hourlyRate)
        }
    }
    @Test fun `ambiguous explicit rate blocks another otherwise valid line`() {
        assertNull(PayslipProfileDraftParserV2.parse("Taux horaire brut 13,63\nTaux horaire brut 14,00 2400,00").hourlyRate)
    }
}
