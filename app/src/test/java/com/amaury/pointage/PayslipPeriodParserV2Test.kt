package com.amaury.pointage
import org.junit.Assert.*
import org.junit.Test
class PayslipPeriodParserV2Test {
    @Test fun `explicit monthly period is proposed`() {
        val period = PayslipPeriodParserV2.parse("Date de paiement 05/10/2026\nPériode de paie : 09/2026")!!
        assertEquals(2026, period.year)
        assertEquals(8, period.monthZeroBased)
    }
    @Test fun `conflicts malformed labels and payment dates do not establish period`() {
        listOf("Période : 09/2026\nMois : 10/2026", "Période : 13/2026", "Date de paiement : 05/10/2026").forEach { assertNull(PayslipPeriodParserV2.parse(it)) }
    }
}
