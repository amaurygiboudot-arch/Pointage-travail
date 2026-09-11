package com.amaury.pointage.v2

import com.amaury.pointage.SalaryCompanyStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RgduStoreIntegrityV2Test {
    @Test
    fun `manual reduction decoder accepts a fully valid array`() {
        val result = CompanyEmployerReductionStoreV2.decode(
            """[{"id":"r1","month":"2026-09","amount":120.5,"source":"DSN 09/2026","note":"confirmé"}]"""
        )

        assertTrue(result.reliable)
        assertEquals(1, result.records.size)
        assertEquals(120.5, result.records.single().totalReductionAmount, 0.0)
    }

    @Test
    fun `manual reduction decoder never hides a malformed entry inside valid data`() {
        val result = CompanyEmployerReductionStoreV2.decode(
            """[
                {"id":"r1","month":"2026-09","amount":120.5,"source":"DSN 09/2026","note":""},
                {"id":"broken","month":"2026-09","source":"source sans montant"}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(1, result.records.size)
        assertTrue(result.warnings.any { it.contains("stockage local incohérent") })
    }

    @Test
    fun `manual reduction decoder blocks invalid root json`() {
        val result = CompanyEmployerReductionStoreV2.decode("not-json")

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `manual reduction decoder refuses coerced source types`() {
        val result = CompanyEmployerReductionStoreV2.decode(
            """[{"id":"r1","month":"2026-09","amount":0,"source":123}]"""
        )

        assertFalse(result.reliable)
    }

    @Test
    fun `manual RGDU unavailable company is fail closed`() {
        val result = CompanyEmployerReductionStoreV2.unavailableReadResult("company-a")

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
        assertTrue(result.warnings.any {
            it.contains("absente", ignoreCase = true) && it.contains("orpheline", ignoreCase = true)
        })
    }

    @Test
    fun `manual RGDU is blocked when company store is unreliable`() {
        val company = SalaryCompanyStore.Company(
            id = "company-a",
            name = "Entreprise A",
            siret = "12345678901234"
        )
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = false,
            warnings = listOf("store entreprises corrompu")
        )

        val blockers = CompanyEmployerReductionStoreV2.companyStoreBlockers(stored, company.id)

        assertTrue(blockers.any { it.contains("corrompu", ignoreCase = true) })
    }

    @Test
    fun `manual RGDU never falls back to orphan company preferences`() {
        val stored = SalaryCompanyStore.ReadResult(emptyList(), reliable = true)

        val blockers = CompanyEmployerReductionStoreV2.companyStoreBlockers(stored, "company-a")

        assertTrue(blockers.any { it.contains("orpheline", ignoreCase = true) })
    }

    @Test
    fun `restored confirmed company keeps RGDU available`() {
        val company = SalaryCompanyStore.Company(
            id = "company-a",
            name = "Entreprise A",
            siret = "12345678901234"
        )
        val stored = SalaryCompanyStore.ReadResult(
            companies = listOf(company),
            reliable = true,
            repairedFromBackup = true,
            warnings = listOf("restauré depuis la copie saine")
        )

        assertTrue(CompanyEmployerReductionStoreV2.companyStoreBlockers(stored, " company-a ").isEmpty())
    }

    @Test
    fun `RGDU context decoder preserves explicit false facts`() {
        val result = CompanyEmployerGeneralReductionContextStoreV2.decode(
            """[{"id":"c1","month":"2026-09","fullMonthPresent":false,"standardCommonLawCaseConfirmed":false,"noOtherEmployerReductionConfirmed":false,"source":"bulletin 09/2026"}]"""
        )

        assertTrue(result.reliable)
        val record = result.records.single()
        assertFalse(record.fullMonthPresent)
        assertFalse(record.standardCommonLawCaseConfirmed)
        assertFalse(record.noOtherEmployerReductionConfirmed)
    }

    @Test
    fun `RGDU context decoder never converts malformed boolean into a confirmed fact`() {
        val result = CompanyEmployerGeneralReductionContextStoreV2.decode(
            """[
                {"id":"c1","month":"2026-09","fullMonthPresent":true,"standardCommonLawCaseConfirmed":true,"noOtherEmployerReductionConfirmed":true,"source":"DSN 09/2026"},
                {"id":"broken","month":"2026-10","fullMonthPresent":"true","standardCommonLawCaseConfirmed":true,"noOtherEmployerReductionConfirmed":true,"source":"import"}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(1, result.records.size)
        assertTrue(result.warnings.any { it.contains("stockage local du contexte mensuel incohérent") })
    }

    @Test
    fun `RGDU context decoder blocks invalid root json`() {
        val result = CompanyEmployerGeneralReductionContextStoreV2.decode("{")

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }
}
