package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConventionMinimumSalaryStoreTest {

    @Test
    fun `explicit empty history is reliable`() {
        val result = V2ConventionMinimumSalaryStore.decodeConfirmed("[]")

        assertTrue(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `malformed json is unreliable`() {
        val result = V2ConventionMinimumSalaryStore.decodeConfirmed("not-json")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `invalid entry makes whole history unreliable without discarding valid evidence`() {
        val result = V2ConventionMinimumSalaryStore.decodeConfirmed(
            """[
                ${ruleJson("R1", "0292", 2000.0)},
                {"idcc":"0292","ruleId":"BROKEN"}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(1, result.rules.size)
    }

    @Test
    fun `duplicated rule id in same convention makes history unreliable`() {
        val result = V2ConventionMinimumSalaryStore.decodeConfirmed(
            """[
                ${ruleJson("R1", "0292", 2000.0)},
                ${ruleJson("R1", "0292", 2100.0)}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `same rule id in two conventions remains reliable`() {
        val result = V2ConventionMinimumSalaryStore.decodeConfirmed(
            """[
                ${ruleJson("R1", "0292", 2000.0)},
                ${ruleJson("R1", "1486", 2100.0)}
            ]""".trimIndent()
        )

        assertTrue(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `invalid amount is rejected and history becomes unreliable`() {
        val result = V2ConventionMinimumSalaryStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", -1.0)}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `unknown periodicity is rejected`() {
        val result = V2ConventionMinimumSalaryStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", 2000.0, periodicity = "WEEKLY")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `extension date without extended status is rejected`() {
        val result = V2ConventionMinimumSalaryStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", 2000.0, extensionStatus = "UNKNOWN")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    private fun ruleJson(
        ruleId: String,
        idcc: String,
        amount: Double,
        periodicity: String = "MONTHLY",
        extensionStatus: String = "EXTENDED"
    ): String = """{
        "idcc":"$idcc",
        "ruleId":"$ruleId",
        "effectiveFrom":"2026-01-01",
        "effectiveTo":null,
        "amount":$amount,
        "periodicity":"$periodicity",
        "source":"legifrance:KALI:KALIARTI000000000",
        "extensionStatus":"$extensionStatus",
        "extensionEffectiveFrom":"2026-01-01",
        "classification":{"coefficient":700}
    }""".trimIndent()
}
