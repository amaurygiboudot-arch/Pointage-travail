package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RgduAnnualContextStoreIntegrityV2Test {
    @Test
    fun `annual context decoder preserves explicit false facts`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[{"id":"a1","year":2026,"fullCalendarYearPresent":false,"standardCommonLawCaseConfirmed":false,"homogeneousAnnualParametersConfirmed":false,"source":"DSN 2026"}]"""
        )

        assertTrue(result.reliable)
        val record = result.records.single()
        assertFalse(record.fullCalendarYearPresent)
        assertFalse(record.standardCommonLawCaseConfirmed)
        assertFalse(record.homogeneousAnnualParametersConfirmed!!)
    }

    @Test
    fun `missing homogeneity and exact parameter fields migrate to unknown never true`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[{"id":"old","year":2026,"fullCalendarYearPresent":true,"standardCommonLawCaseConfirmed":true,"source":"ancien contexte"}]"""
        )

        assertTrue(result.reliable)
        assertEquals(1, result.records.size)
        val record = result.records.single()
        assertNull(record.homogeneousAnnualParametersConfirmed)
        assertNull(record.confirmedWorkforceBand)
        assertNull(record.confirmedContractType)
        assertNull(record.confirmedContractualWeeklyMinutes)
    }

    @Test
    fun `valid exact historical annual parameters are preserved`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[{"id":"a1","year":2026,"fullCalendarYearPresent":true,"standardCommonLawCaseConfirmed":true,"homogeneousAnnualParametersConfirmed":true,"source":"DSN 2026","confirmedWorkforceBand":"AT_LEAST_50","confirmedContractType":"FULL_TIME","confirmedContractualWeeklyMinutes":2100}]"""
        )

        assertTrue(result.reliable)
        val record = result.records.single()
        assertEquals(EmployerWorkforceContributionsV2.Band.AT_LEAST_50, record.confirmedWorkforceBand)
        assertEquals(ContractTypeV2.FULL_TIME, record.confirmedContractType)
        assertEquals(2100, record.confirmedContractualWeeklyMinutes)
    }

    @Test
    fun `malformed homogeneity value blocks store reliability`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[
                {"id":"ok","year":2026,"fullCalendarYearPresent":true,"standardCommonLawCaseConfirmed":true,"homogeneousAnnualParametersConfirmed":true,"source":"DSN 2026"},
                {"id":"broken","year":2025,"fullCalendarYearPresent":true,"standardCommonLawCaseConfirmed":true,"homogeneousAnnualParametersConfirmed":"true","source":"import"}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(1, result.records.size)
        assertTrue(result.warnings.any { it.contains("stockage local du contexte incohérent", ignoreCase = true) })
    }

    @Test
    fun `unknown workforce enum blocks store reliability`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[{"id":"broken","year":2026,"fullCalendarYearPresent":true,"standardCommonLawCaseConfirmed":true,"homogeneousAnnualParametersConfirmed":true,"source":"DSN 2026","confirmedWorkforceBand":"UNKNOWN"}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `unknown contract enum blocks store reliability`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[{"id":"broken","year":2026,"fullCalendarYearPresent":true,"standardCommonLawCaseConfirmed":true,"homogeneousAnnualParametersConfirmed":true,"source":"DSN 2026","confirmedContractType":"CDI"}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `fractional confirmed weekly minutes block store reliability`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[{"id":"broken","year":2026,"fullCalendarYearPresent":true,"standardCommonLawCaseConfirmed":true,"homogeneousAnnualParametersConfirmed":true,"source":"DSN 2026","confirmedContractualWeeklyMinutes":2100.5}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `negative confirmed weekly minutes block store reliability`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[{"id":"broken","year":2026,"fullCalendarYearPresent":true,"standardCommonLawCaseConfirmed":true,"homogeneousAnnualParametersConfirmed":true,"source":"DSN 2026","confirmedContractualWeeklyMinutes":-1}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `missing required annual fact blocks store reliability`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[{"id":"broken","year":2026,"standardCommonLawCaseConfirmed":true,"source":"DSN 2026"}]"""
        )

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `invalid root json blocks annual store`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode("not-json")

        assertFalse(result.reliable)
        assertTrue(result.records.isEmpty())
    }
}
