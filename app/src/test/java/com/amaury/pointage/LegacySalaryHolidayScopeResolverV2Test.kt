package com.amaury.pointage

import com.amaury.pointage.v2.engine.FrenchPublicHolidayCalendarV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySalaryHolidayScopeResolverV2Test {
    @Test
    fun `store entreprises non fiable bloque la fiabilite du brut legacy`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = emptyList(),
            reliable = false,
            warnings = listOf("store entreprises corrompu")
        )

        val result = LegacySalaryHolidayScopeResolverV2.resolve(stored, "legacy-company")

        assertFalse(result.companyStoreReliable)
        assertEquals(FrenchPublicHolidayCalendarV2.Jurisdiction.ADDRESS_UNKNOWN, result.scope?.jurisdiction)
        assertTrue(result.warnings.any { it.contains("corrompu", ignoreCase = true) })
    }

    @Test
    fun `ancien employeur non rattache conserve le mode legacy sans inventer de territoire`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = emptyList(),
            reliable = true
        )

        val result = LegacySalaryHolidayScopeResolverV2.resolve(stored, "legacy-company")

        assertTrue(result.companyStoreReliable)
        assertEquals(FrenchPublicHolidayCalendarV2.Jurisdiction.ADDRESS_UNKNOWN, result.scope?.jurisdiction)
        assertFalse(result.scope?.complete ?: true)
        assertTrue(result.warnings.any { it.contains("ancien employeur", ignoreCase = true) })
    }

    @Test
    fun `entreprise confirmee utilise son vrai calendrier territorial`() {
        val company = SalaryCompanyStore.Company(
            id = "company-a",
            name = "Entreprise A",
            siret = "12345678901234",
            address = "1 rue des Ateliers, 85190 Aizenay"
        )
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = true
        )

        val result = LegacySalaryHolidayScopeResolverV2.resolve(stored, " company-a ")

        assertTrue(result.companyStoreReliable)
        assertEquals(FrenchPublicHolidayCalendarV2.Jurisdiction.COMMON_FRANCE, result.scope?.jurisdiction)
        assertTrue(result.scope?.complete == true)
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `profil sans employeur laisse le calcul de fiche incomplete gerer le blocage`() {
        val stored = SalaryCompanyStore.ReadResult(emptyList(), reliable = false)

        val result = LegacySalaryHolidayScopeResolverV2.resolve(stored, " ")

        assertTrue(result.companyStoreReliable)
        assertEquals(null, result.scope)
        assertTrue(result.warnings.isEmpty())
    }
}
