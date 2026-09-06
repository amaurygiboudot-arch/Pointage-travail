package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalPayrollSourceStoreStatusTest {
    @Test
    fun `VIGUEUR reste applicable`() {
        assertTrue(LegalPayrollSourceStoreV2.isApplicableStatus("VIGUEUR"))
    }

    @Test
    fun `VIGUEUR_DIFF est applicable quand la periode couvre la date de paie`() {
        assertTrue(LegalPayrollSourceStoreV2.isApplicableStatus("VIGUEUR_DIFF"))
    }

    @Test
    fun `un autre etat juridique reste exclu`() {
        assertFalse(LegalPayrollSourceStoreV2.isApplicableStatus("ABROGE"))
    }
}
