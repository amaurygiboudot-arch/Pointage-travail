package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class CompanyAtMpRateDialogV2Test {
    @Test
    fun `ancienne valeur reste un brouillon numerique et zero explicite est accepte`() {
        assertEquals(2.08, CompanyAtMpRateDialogV2.draftRatePercent("2,08")!!, 0.0)
        assertEquals(0.0, CompanyAtMpRateDialogV2.draftRatePercent("0")!!, 0.0)
        listOf(null, "", "NaN", "Infinity", "-1", "101").forEach { assertNull(CompanyAtMpRateDialogV2.draftRatePercent(it)) }
    }

    @Test
    fun `date obligatoire stricte ne cree aucune periode par defaut`() {
        assertEquals(LocalDate.of(2028, 2, 29), CompanyAtMpRateDialogV2.parseEffectiveDate("29/02/2028"))
        listOf("", "31/02/2026", "29/02/2026", "2026-01-01").forEach { assertNull(CompanyAtMpRateDialogV2.parseEffectiveDate(it)) }
    }
}
