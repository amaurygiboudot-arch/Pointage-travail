package com.amaury.pointage.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmployerCompanyStoresLifetimeV2Test {
    @Test
    fun `maladie famille sans entreprise reste fail closed`() {
        val result = CompanyHealthFamilyStoreV2.companyUnavailableResult()
        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `chomage AGS sans entreprise reste fail closed`() {
        val result = CompanyUnemploymentAgsStoreV2.companyUnavailableResult()
        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `apprentissage sans entreprise reste fail closed`() {
        val result = CompanyApprenticeshipTaxStoreV2.companyUnavailableResult()
        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `mobilite sans entreprise reste fail closed`() {
        val result = CompanyMobilityContributionStoreV2.companyUnavailableResult()
        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }
}
