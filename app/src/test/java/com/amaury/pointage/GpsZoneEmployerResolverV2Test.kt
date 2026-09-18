package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsZoneEmployerResolverV2Test {
    private val companies = listOf("company-a", "company-b", "company-c")

    @Test
    fun `un company id stable valide est prioritaire sur un ancien slot`() {
        val result = resolveGpsZoneEmployerV2(
            companyId = "company-c",
            legacyCompanySlot = 1,
            companiesReliable = true,
            confirmedCompanyIds = companies
        )

        assertEquals(GpsZoneEmployerResolutionV2.UseCompany("company-c"), result)
    }

    @Test
    fun `une zone sans association conserve le choix utilisateur courant`() {
        val result = resolveGpsZoneEmployerV2(
            companyId = null,
            legacyCompanySlot = null,
            companiesReliable = true,
            confirmedCompanyIds = companies
        )

        assertTrue(result is GpsZoneEmployerResolutionV2.KeepCurrent)
    }

    @Test
    fun `un ancien slot encore resolvable migre vers un company id stable`() {
        val result = resolveGpsZoneEmployerV2(
            companyId = null,
            legacyCompanySlot = 2,
            companiesReliable = true,
            confirmedCompanyIds = companies
        )

        assertEquals(GpsZoneEmployerResolutionV2.UseCompany("company-b"), result)
    }

    @Test
    fun `un company id inconnu bloque au lieu de retomber sur le slot`() {
        val result = resolveGpsZoneEmployerV2(
            companyId = "company-supprimee",
            legacyCompanySlot = 1,
            companiesReliable = true,
            confirmedCompanyIds = companies
        )

        assertTrue(result is GpsZoneEmployerResolutionV2.Block)
    }

    @Test
    fun `un stockage entreprises non fiable bloque une association stable`() {
        val result = resolveGpsZoneEmployerV2(
            companyId = "company-a",
            legacyCompanySlot = null,
            companiesReliable = false,
            confirmedCompanyIds = companies
        )

        assertTrue(result is GpsZoneEmployerResolutionV2.Block)
    }

    @Test
    fun `un ancien slot sans entreprise correspondante bloque`() {
        val result = resolveGpsZoneEmployerV2(
            companyId = null,
            legacyCompanySlot = 2,
            companiesReliable = true,
            confirmedCompanyIds = listOf("company-a")
        )

        assertTrue(result is GpsZoneEmployerResolutionV2.Block)
    }
}
