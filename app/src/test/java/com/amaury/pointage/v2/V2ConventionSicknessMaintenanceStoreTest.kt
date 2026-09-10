package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConventionSicknessMaintenanceStoreTest {

    @Test
    fun `explicit empty history is reliable`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed("[]")

        assertTrue(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `malformed json is unreliable`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed("not-json")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `invalid entry makes whole history unreliable without discarding valid evidence`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed(
            """[
                ${ruleJson("R1", "0292")},
                {"idcc":"0292","ruleId":"BROKEN"}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(1, result.rules.size)
    }

    @Test
    fun `duplicated rule id in same convention makes history unreliable`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed(
            """[
                ${ruleJson("R1", "0292")},
                ${ruleJson("R1", "0292", bandRate = 0.8)}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `same rule id in two conventions remains reliable`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed(
            """[
                ${ruleJson("R1", "0292")},
                ${ruleJson("R1", "1486")}
            ]""".trimIndent()
        )

        assertTrue(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `unknown band consumption scope is rejected`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", bandConsumptionScope = "UNKNOWN_VALUE")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `duplicated seniority tiers are rejected`() {
        val tiers = """[
            ${tierJson(0)},
            ${tierJson(0)}
        ]""".trimIndent()
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", tiers = tiers)}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `waiting days with no waiting policy are rejected`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", waitingDays = 1)}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `unknown reference basis is rejected`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", referenceBasis = "UNKNOWN_VALUE")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `extension date without extended status is rejected`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", extensionStatus = "UNKNOWN")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `blank official source is rejected`() {
        val result = V2ConventionSicknessMaintenanceStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", source = "")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    private fun ruleJson(
        ruleId: String,
        idcc: String,
        bandRate: Double = 1.0,
        bandConsumptionScope: String = "ANNUAL_CUMULATIVE",
        waitingDays: Int = 0,
        referenceBasis: String = "NET",
        extensionStatus: String = "EXTENDED",
        source: String = "legifrance:KALI:KALIARTI000000000",
        tiers: String = tierJson(0, bandRate, bandConsumptionScope)
    ): String = """{
        "idcc":"$idcc",
        "ruleId":"$ruleId",
        "effectiveFrom":"2026-01-01",
        "effectiveTo":null,
        "classification":{"coefficient":700},
        "professionalStatus":"NON_CADRE",
        "minimumSeniorityMonths":0,
        "tiers":[$tiers],
        "referenceBasis":"$referenceBasis",
        "waitingPolicy":"NONE",
        "waitingDays":$waitingDays,
        "ssCoverageAfterDays":null,
        "source":"$source",
        "extensionStatus":"$extensionStatus",
        "extensionEffectiveFrom":"2026-01-01"
    }""".trimIndent()

    private fun tierJson(
        minimumSeniorityMonths: Int,
        bandRate: Double = 1.0,
        bandConsumptionScope: String = "ANNUAL_CUMULATIVE"
    ): String = """{
        "minimumSeniorityMonths":$minimumSeniorityMonths,
        "annualLimitDays":30,
        "perStopLimitDays":30,
        "bandConsumptionScope":"$bandConsumptionScope",
        "bands":[{"days":30,"rate":$bandRate,"label":"maintien"}]
    }""".trimIndent()
}
