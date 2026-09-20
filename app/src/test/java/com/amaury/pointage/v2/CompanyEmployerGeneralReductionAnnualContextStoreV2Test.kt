package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import com.amaury.pointage.v2.model.ContractTypeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyEmployerGeneralReductionAnnualContextStoreV2Test {
    @Test
    fun `explicit FNAL treatment is decoded and preserved`() {
        val stored = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[
                {
                  "id":"annual-2026",
                  "year":2026,
                  "fullCalendarYearPresent":true,
                  "standardCommonLawCaseConfirmed":true,
                  "homogeneousAnnualParametersConfirmed":true,
                  "source":"DSN annuelle 2026",
                  "confirmedFnalTreatment":"CAPPED_0_1_PERCENT",
                  "confirmedContractType":"FULL_TIME",
                  "confirmedContractualWeeklyMinutes":2100
                }
            ]""".trimIndent()
        )

        assertTrue(stored.reliable)
        assertTrue(stored.warnings.isEmpty())
        assertEquals(1, stored.records.size)
        val record = stored.records.single()
        assertEquals(
            EmployerWorkforceContributionsV2.FnalTreatment.CAPPED_0_1_PERCENT,
            record.confirmedFnalTreatment
        )
        assertEquals(ContractTypeV2.FULL_TIME, record.confirmedContractType)
        assertEquals(2100, record.confirmedContractualWeeklyMinutes)
    }

    @Test
    fun `legacy workforce band never becomes an inferred FNAL treatment`() {
        val stored = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[
                {
                  "id":"legacy-2026",
                  "year":2026,
                  "fullCalendarYearPresent":true,
                  "standardCommonLawCaseConfirmed":true,
                  "homogeneousAnnualParametersConfirmed":true,
                  "source":"Ancien contexte RGDU",
                  "confirmedWorkforceBand":"AT_LEAST_50",
                  "confirmedContractType":"FULL_TIME",
                  "confirmedContractualWeeklyMinutes":2100
                }
            ]""".trimIndent()
        )

        assertTrue(stored.reliable)
        assertEquals(1, stored.records.size)
        assertNull(stored.records.single().confirmedFnalTreatment)

        val resolved = CompanyEmployerGeneralReductionAnnualContextStoreV2.resolve(stored, 2026)
        assertTrue(resolved.reliable)
        assertNull(resolved.confirmedFnalTreatment)
        assertTrue(resolved.warnings.any { it.contains("FNAL/logement", ignoreCase = true) })
    }

    @Test
    fun `malformed explicit FNAL treatment makes storage unreliable`() {
        val stored = CompanyEmployerGeneralReductionAnnualContextStoreV2.decode(
            """[
                {
                  "id":"annual-2026",
                  "year":2026,
                  "fullCalendarYearPresent":true,
                  "standardCommonLawCaseConfirmed":true,
                  "homogeneousAnnualParametersConfirmed":true,
                  "source":"DSN annuelle 2026",
                  "confirmedFnalTreatment":"UNKNOWN_REGIME",
                  "confirmedContractType":"FULL_TIME",
                  "confirmedContractualWeeklyMinutes":2100
                }
            ]""".trimIndent()
        )

        assertFalse(stored.reliable)
        assertTrue(stored.records.isEmpty())
        assertTrue(stored.warnings.any { it.contains("stockage", ignoreCase = true) })
    }
}
