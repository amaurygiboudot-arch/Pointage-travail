package com.amaury.pointage.v2.engine

import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.NetSalaryReferencePolicyV2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CompanyPayrollOverridesCompanyStoreReliabilityV2Test {
    private val company = SalaryCompanyStore.Company(
        id = "company-a",
        name = "Entreprise A",
        siret = "12345678901234",
        idcc = "292"
    )

    @Test
    fun `un store non fiable ne peut pas fournir une entreprise meme si elle est partiellement lisible`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = false,
            warnings = listOf("store entreprises corrompu")
        )

        assertNull(CompanyPayrollOverridesV2.confirmedCompany(stored, company.id))
        assertTrue(CompanyPayrollOverridesV2.companyStoreBlockers(stored, company.id).any {
            it.contains("corrompu", ignoreCase = true)
        })
    }

    @Test
    fun `une restauration fiable reste exploitable`() {
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = true,
            repairedFromBackup = true,
            warnings = listOf("restaure depuis la copie saine")
        )

        assertSame(company, CompanyPayrollOverridesV2.confirmedCompany(stored, company.id))
    }

    @Test
    fun `une entreprise absente du store fiable ne reactive aucune preference orpheline`() {
        val stored = SalaryCompanyStore.ReadResult(emptyList(), reliable = true)
        val warnings = CompanyPayrollOverridesV2.companyStoreBlockers(stored, company.id)
        val snapshot = CompanyPayrollOverridesV2.unresolvedCompanySnapshot(
            companyId = company.id,
            referenceDate = LocalDate.of(2026, 9, 30),
            warnings = warnings
        )

        assertNull(snapshot.idcc)
        assertNull(snapshot.contractType)
        assertNull(snapshot.incomeTaxRate)
        assertNull(snapshot.unpaidAbsenceDays)
        assertFalse(snapshot.protectionCategory.confirmed)
        assertTrue(snapshot.protectionCategory.category == PlasturgieProtectionCategoryV2.Category.TO_CONFIRM)
        assertFalse(snapshot.verifiedProvidentStoreReliable)
        assertFalse(snapshot.verifiedCompanyProvidentStoreReliable)
        assertTrue(snapshot.warnings.any { it.contains("orpheline", ignoreCase = true) })
    }

    @Test
    fun `un snapshot entreprise non fiable ne devient pas une reference nette`() {
        val snapshot = CompanyPayrollOverridesV2.unresolvedCompanySnapshot(
            companyId = company.id,
            referenceDate = LocalDate.of(2026, 9, 30),
            warnings = listOf("store entreprises non fiable")
        )
        val result = NetSalaryEngineV2.calculate(
            gross = 2_000.0,
            year = 2026,
            company = snapshot,
            complementaryMinutes = 0
        )

        assertFalse(result.complete)
        assertNull(NetSalaryReferencePolicyV2.beforeIncomeTax(result))
        assertTrue(result.warnings.any { it.contains("non fiable", ignoreCase = true) })
    }
}
