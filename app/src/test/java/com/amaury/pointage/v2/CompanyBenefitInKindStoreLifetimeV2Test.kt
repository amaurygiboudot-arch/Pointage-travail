package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class CompanyBenefitInKindStoreLifetimeV2Test {
    @Test
    fun `avantages sans entreprise restent fail closed`() {
        val result = CompanyBenefitInKindStoreV2.companyUnavailableReadResult()
        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `confirmation mensuelle sans entreprise reste fail closed`() {
        val result = CompanyBenefitInKindStoreV2.companyUnavailableConfirmationResult()
        assertFalse(result.reliable)
        assertTrue(result.confirmations.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `paie avantages sans entreprise ne devient jamais fiable`() {
        val snapshot = CompanyBenefitInKindStoreV2.resolve(
            CompanyBenefitInKindStoreV2.companyUnavailableReadResult(),
            CompanyBenefitInKindStoreV2.companyUnavailableConfirmationResult(),
            YearMonth.of(2026, 9)
        )
        assertFalse(snapshot.reliable)
        assertTrue(snapshot.applied.isEmpty())
        assertTrue(snapshot.warnings.isNotEmpty())
    }
}
