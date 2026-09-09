package com.amaury.pointage.v2

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
    fun `missing homogeneity field migrates to unknown never true`() {
        val result = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[{"id":"old","year":2026,"fullCalendarYearPresent":true,"standardCommonLawCaseConfirmed":true,"source":"ancien contexte"}]"""
        )

        assertTrue(result.reliable)
        assertEquals(1, result.records.size)
        assertNull(result.records.single().homogeneousAnnualParametersConfirmed)
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
