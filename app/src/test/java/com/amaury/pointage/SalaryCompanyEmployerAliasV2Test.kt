package com.amaury.pointage

import org.junit.Assert.assertEquals
import org.junit.Test

class SalaryCompanyEmployerAliasV2Test {
    @Test
    fun `ambiguous legacy alias is rejected for every company`() {
        val first = SalaryCompanyStore.Company("company_a", "ACME", "")
        val second = SalaryCompanyStore.Company("company_b", "ACME", "")
        val companies = listOf(first, second)
        val ids = mapOf(
            first.id to setOf(first.id, "company_1"),
            second.id to setOf(second.id, "company_1")
        )

        assertEquals(
            setOf(first.id),
            SalaryCompanyStore.retainUnambiguousEmployerIds(first.id, companies) { ids.getValue(it.id) }
        )
        assertEquals(
            setOf(second.id),
            SalaryCompanyStore.retainUnambiguousEmployerIds(second.id, companies) { ids.getValue(it.id) }
        )
    }

    @Test
    fun `unique legacy alias remains accepted for migration compatibility`() {
        val first = SalaryCompanyStore.Company("company_a", "ACME", "")
        val second = SalaryCompanyStore.Company("company_b", "OTHER", "")
        val companies = listOf(first, second)
        val ids = mapOf(
            first.id to setOf(first.id, "company_1"),
            second.id to setOf(second.id)
        )

        assertEquals(
            linkedSetOf(first.id, "company_1"),
            SalaryCompanyStore.retainUnambiguousEmployerIds(first.id, companies) { ids.getValue(it.id) }
        )
    }

    @Test
    fun `legacy alias cannot steal another company stable id`() {
        val first = SalaryCompanyStore.Company("company_a", "ACME", "")
        val stableAliasOwner = SalaryCompanyStore.Company("company_1", "OTHER", "")
        val companies = listOf(first, stableAliasOwner)
        val ids = mapOf(
            first.id to setOf(first.id, "company_1"),
            stableAliasOwner.id to setOf(stableAliasOwner.id)
        )

        assertEquals(
            setOf(first.id),
            SalaryCompanyStore.retainUnambiguousEmployerIds(first.id, companies) { ids.getValue(it.id) }
        )
    }
}
