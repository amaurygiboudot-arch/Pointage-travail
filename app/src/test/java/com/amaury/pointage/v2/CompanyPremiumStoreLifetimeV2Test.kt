package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyPremiumStoreLifetimeV2Test {
    @Test
    fun `records de primes sans entreprise restent fail closed`() {
        val result = CompanyPremiumStoreV2.companyUnavailableReadResult()
        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `confirmation mensuelle sans entreprise reste fail closed`() {
        val result = CompanyPremiumStoreV2.companyUnavailableConfirmationResult()
        assertFalse(result.reliable)
        assertTrue(result.confirmations.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `paie primes sans entreprise ne devient jamais fiable`() {
        val snapshot = CompanyPremiumStoreV2.resolve(
            CompanyPremiumStoreV2.companyUnavailableReadResult(),
            CompanyPremiumStoreV2.companyUnavailableConfirmationResult(),
            YearMonth.of(2026, 9)
        )
        assertFalse(snapshot.reliable)
        assertTrue(snapshot.applied.isEmpty())
        assertTrue(snapshot.warnings.isNotEmpty())
    }
}
