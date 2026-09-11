package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryCompanyIdentityResolverV2Test {
    @Test
    fun `unreliable company store blocks explicit add resolution`() {
        val stored = SalaryCompanyStore.ReadResult(emptyList(), reliable = false)
        val incoming = SalaryCompanyStore.Company("siret_12345678901234", "A", "12345678901234")

        val result = SalaryCompanyIdentityResolverV2.resolve(stored, incoming)

        assertNull(result.company)
        assertEquals(SalaryCompanyIdentityResolverV2.Failure.UNRELIABLE_STORE, result.failure)
    }

    @Test
    fun `new siret keeps incoming stable id`() {
        val stored = SalaryCompanyStore.ReadResult(emptyList(), reliable = true)
        val incoming = SalaryCompanyStore.Company("siret_12345678901234", "A", "12345678901234")

        val result = SalaryCompanyIdentityResolverV2.resolve(stored, incoming)

        assertFalse(result.existing)
        assertEquals(incoming, result.company)
    }

    @Test
    fun `same siret preserves historical stable id`() {
        val existing = SalaryCompanyStore.Company(
            id = "legacy_1",
            name = "Ancien nom",
            siret = "12345678901234",
            address = "Ancienne adresse",
            conventionName = "Convention confirmée",
            idcc = "0045"
        )
        val incoming = SalaryCompanyStore.Company(
            id = "siret_12345678901234",
            name = "Nouveau nom",
            siret = "12345678901234",
            address = "Nouvelle adresse",
            conventionName = "",
            idcc = ""
        )

        val result = SalaryCompanyIdentityResolverV2.resolve(
            SalaryCompanyStore.ReadResult(listOf(existing), reliable = true),
            incoming
        )

        assertTrue(result.existing)
        assertEquals("legacy_1", result.company?.id)
        assertEquals("Nouveau nom", result.company?.name)
        assertEquals("Nouvelle adresse", result.company?.address)
        assertEquals("Convention confirmée", result.company?.conventionName)
        assertEquals("0045", result.company?.idcc)
    }

    @Test
    fun `blank incoming fields do not erase known local values`() {
        val existing = SalaryCompanyStore.Company(
            id = "company_a",
            name = "Entreprise A",
            siret = "12345678901234",
            address = "1 rue A",
            conventionName = "Convention A",
            idcc = "0045"
        )
        val incoming = SalaryCompanyStore.Company(
            id = "company_a",
            name = "",
            siret = "",
            address = "",
            conventionName = "",
            idcc = ""
        )

        val result = SalaryCompanyIdentityResolverV2.resolve(
            SalaryCompanyStore.ReadResult(listOf(existing), reliable = true),
            incoming
        )

        assertEquals(existing, result.company)
        assertTrue(result.existing)
    }

    @Test
    fun `multiple local matches for one siret remain ambiguous`() {
        val first = SalaryCompanyStore.Company("legacy_1", "A", "12345678901234")
        val second = SalaryCompanyStore.Company("legacy_2", "B", "12345678901234")
        val incoming = SalaryCompanyStore.Company("siret_12345678901234", "A", "12345678901234")

        val result = SalaryCompanyIdentityResolverV2.resolve(
            SalaryCompanyStore.ReadResult(listOf(first, second), reliable = true),
            incoming
        )

        assertNull(result.company)
        assertEquals(SalaryCompanyIdentityResolverV2.Failure.AMBIGUOUS_SIRET, result.failure)
    }

    @Test
    fun `exact id conflicting with another company siret is rejected`() {
        val exact = SalaryCompanyStore.Company("company_a", "A", "11111111111111")
        val siretOwner = SalaryCompanyStore.Company("company_b", "B", "12345678901234")
        val incoming = SalaryCompanyStore.Company("company_a", "A", "12345678901234")

        val result = SalaryCompanyIdentityResolverV2.resolve(
            SalaryCompanyStore.ReadResult(listOf(exact, siretOwner), reliable = true),
            incoming
        )

        assertNull(result.company)
        assertEquals(SalaryCompanyIdentityResolverV2.Failure.AMBIGUOUS_SIRET, result.failure)
    }

    @Test
    fun `invalid non empty siret is rejected`() {
        val stored = SalaryCompanyStore.ReadResult(emptyList(), reliable = true)
        val incoming = SalaryCompanyStore.Company("company_a", "A", "123")

        val result = SalaryCompanyIdentityResolverV2.resolve(stored, incoming)

        assertNull(result.company)
        assertEquals(SalaryCompanyIdentityResolverV2.Failure.INVALID_IDENTITY, result.failure)
    }
}
