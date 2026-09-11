package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryCompanyStoreReliabilityV2Test {
    @Test
    fun `empty array is a reliable explicit absence`() {
        val result = SalaryCompanyStore.decodeCompanies("[]")

        assertTrue(result.reliable)
        assertTrue(result.companies.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `malformed array never becomes a reliable empty company list`() {
        val result = SalaryCompanyStore.decodeCompanies("{not-an-array}")

        assertFalse(result.reliable)
        assertTrue(result.companies.isEmpty())
        assertTrue(result.warnings.any { it.contains("aucune entreprise ni absence") })
    }

    @Test
    fun `partially readable array remains unreliable while preserving recoverable entries`() {
        val raw = """[
            {"id":"siret_12345678901234","name":"Entreprise A","siret":"12345678901234","address":"","conventionName":"","idcc":"0045"},
            "broken-entry"
        ]""".trimIndent()

        val result = SalaryCompanyStore.decodeCompanies(raw)

        assertFalse(result.reliable)
        assertEquals(1, result.companies.size)
        assertEquals("siret_12345678901234", result.companies.single().id)
    }

    @Test
    fun `duplicate company ids make the store unreliable`() {
        val raw = """[
            {"id":"same","name":"A","siret":"","address":"","conventionName":"","idcc":""},
            {"id":"same","name":"B","siret":"","address":"","conventionName":"","idcc":""}
        ]""".trimIndent()

        val result = SalaryCompanyStore.decodeCompanies(raw)

        assertFalse(result.reliable)
        assertEquals(2, result.companies.size)
    }

    @Test
    fun `valid last known good copy repairs a corrupted primary store`() {
        val backup = """[
            {"id":"company_a","name":"Entreprise A","siret":"","address":"","conventionName":"","idcc":""}
        ]""".trimIndent()

        val resolution = SalaryCompanyStore.resolveStoredCompanies(
            primaryRaw = "broken",
            backupRaw = backup
        )

        assertEquals(SalaryCompanyStore.StorageSource.LAST_KNOWN_GOOD, resolution.source)
        assertTrue(resolution.result.reliable)
        assertTrue(resolution.result.repairedFromBackup)
        assertEquals("company_a", resolution.result.companies.single().id)
        assertTrue(resolution.result.warnings.any { it.contains("restauré") })
    }

    @Test
    fun `corrupted primary without valid backup remains fail closed`() {
        val resolution = SalaryCompanyStore.resolveStoredCompanies(
            primaryRaw = "broken",
            backupRaw = "also-broken"
        )

        assertEquals(SalaryCompanyStore.StorageSource.NONE, resolution.source)
        assertFalse(resolution.result.reliable)
        assertTrue(resolution.result.companies.isEmpty())
    }

    @Test
    fun `partially recovered companies cannot be used to resolve employer aliases`() {
        val company = SalaryCompanyStore.Company(
            id = "company_a",
            name = "Entreprise A",
            siret = "12345678901234"
        )
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = false,
            warnings = listOf("stockage incohérent")
        )

        assertNull(SalaryCompanyStore.companiesForAliasResolution(stored))
    }

    @Test
    fun `repaired reliable companies remain usable for employer alias resolution`() {
        val company = SalaryCompanyStore.Company(
            id = "company_a",
            name = "Entreprise A",
            siret = "12345678901234"
        )
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = true,
            repairedFromBackup = true,
            warnings = listOf("restauré")
        )

        assertEquals(listOf(company), SalaryCompanyStore.companiesForAliasResolution(stored))
    }

    @Test
    fun `reliable explicit absence stays usable for alias resolution`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = emptyList(),
            reliable = true
        )

        assertEquals(emptyList<SalaryCompanyStore.Company>(), SalaryCompanyStore.companiesForAliasResolution(stored))
    }

    @Test
    fun `confirmed company requires a reliable exact stable id`() {
        val company = SalaryCompanyStore.Company("company_a", "A", "12345678901234")
        val unreliable = SalaryCompanyStore.ReadResult(listOf(company), reliable = false)
        val reliable = SalaryCompanyStore.ReadResult(listOf(company), reliable = true)

        assertNull(SalaryCompanyStore.confirmedCompany(unreliable, "company_a"))
        assertNull(SalaryCompanyStore.confirmedCompany(reliable, "company_b"))
        assertEquals(company, SalaryCompanyStore.confirmedCompany(reliable, " company_a "))
    }

    @Test
    fun `existing update cannot resurrect a deleted company`() {
        val deleted = SalaryCompanyStore.ReadResult(emptyList(), reliable = true)
        val staleUpdate = SalaryCompanyStore.Company(
            id = "company_a",
            name = "Ancienne entreprise",
            siret = "12345678901234",
            idcc = "0045"
        )

        assertNull(SalaryCompanyStore.companiesAfterMutation(deleted, staleUpdate, allowInsert = false))
    }

    @Test
    fun `existing update cannot hijack another company through the same siret`() {
        val existing = SalaryCompanyStore.Company(
            id = "company_current",
            name = "Entreprise actuelle",
            siret = "12345678901234"
        )
        val staleUpdate = SalaryCompanyStore.Company(
            id = "company_deleted",
            name = "Entreprise supprimée",
            siret = "12345678901234"
        )
        val stored = SalaryCompanyStore.ReadResult(listOf(existing), reliable = true)

        assertNull(SalaryCompanyStore.companiesAfterMutation(stored, staleUpdate, allowInsert = false))
    }

    @Test
    fun `existing update replaces only its exact stable id`() {
        val before = SalaryCompanyStore.Company("company_a", "Avant", "12345678901234")
        val updated = before.copy(name = "Après", idcc = "0045")
        val stored = SalaryCompanyStore.ReadResult(listOf(before), reliable = true)

        val companies = SalaryCompanyStore.companiesAfterMutation(stored, updated, allowInsert = false)

        assertEquals(listOf(updated), companies)
    }

    @Test
    fun `explicit add flow may insert a new company`() {
        val company = SalaryCompanyStore.Company("company_a", "A", "12345678901234")
        val stored = SalaryCompanyStore.ReadResult(emptyList(), reliable = true)

        assertEquals(
            listOf(company),
            SalaryCompanyStore.companiesAfterMutation(stored, company, allowInsert = true)
        )
    }

    @Test
    fun `company mutation remains blocked when store is unreliable`() {
        val company = SalaryCompanyStore.Company("company_a", "A", "12345678901234")
        val stored = SalaryCompanyStore.ReadResult(listOf(company), reliable = false)

        assertNull(SalaryCompanyStore.companiesAfterMutation(stored, company, allowInsert = false))
        assertNull(SalaryCompanyStore.companiesAfterMutation(stored, company, allowInsert = true))
    }
}
