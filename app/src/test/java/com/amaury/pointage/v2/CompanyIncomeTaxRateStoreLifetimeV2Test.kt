package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyIncomeTaxRateStoreLifetimeV2Test {
    @Test
    fun `taux PAS sans entreprise reste fail closed`() {
        val result = CompanyIncomeTaxRateStoreV2.companyUnavailableResult()

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `resolution PAS sans entreprise ne reutilise jamais le fallback legacy`() {
        val snapshot = CompanyIncomeTaxRateStoreV2.resolve(
            CompanyIncomeTaxRateStoreV2.companyUnavailableResult(),
            YearMonth.of(2026, 9)
        )

        assertFalse(snapshot.reliable)
        assertTrue(snapshot.hasDatedRecords)
        assertNull(snapshot.rate)
        assertNull(snapshot.ratePercent)
        assertNull(snapshot.source)
        assertTrue(snapshot.warnings.isNotEmpty())
    }
}
