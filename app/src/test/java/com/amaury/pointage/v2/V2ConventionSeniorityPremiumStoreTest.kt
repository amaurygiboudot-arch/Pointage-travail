package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ConventionSeniorityPremiumStoreTest {

    @Test
    fun `explicit empty history is reliable`() {
        val result = V2ConventionSeniorityPremiumStore.decodeConfirmed("[]")

        assertTrue(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `malformed json is unreliable`() {
        val result = V2ConventionSeniorityPremiumStore.decodeConfirmed("not-json")

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `invalid entry makes whole history unreliable without using it`() {
        val result = V2ConventionSeniorityPremiumStore.decodeConfirmed(
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
        val result = V2ConventionSeniorityPremiumStore.decodeConfirmed(
            """[
                ${ruleJson("R1", "0292")},
                ${ruleJson("R1", "0292", rate = 0.03)}
            ]""".trimIndent()
        )

        assertFalse(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `same rule id in two conventions remains reliable`() {
        val result = V2ConventionSeniorityPremiumStore.decodeConfirmed(
            """[
                ${ruleJson("R1", "0292")},
                ${ruleJson("R1", "1486")}
            ]""".trimIndent()
        )

        assertTrue(result.reliable)
        assertEquals(2, result.rules.size)
    }

    @Test
    fun `duplicate seniority steps make rule unreliable`() {
        val steps = """[
            {"years":3,"rate":0.024,"fixedMonthlyAmount":null},
            {"years":3,"rate":0.03,"fixedMonthlyAmount":null}
        ]""".trimIndent()
        val result = V2ConventionSeniorityPremiumStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", steps = steps)}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `unknown basis is rejected`() {
        val result = V2ConventionSeniorityPremiumStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", basis = "UNKNOWN_BASIS")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `extension date without extended status is rejected`() {
        val result = V2ConventionSeniorityPremiumStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", extensionStatus = "UNKNOWN")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `blank official source is rejected`() {
        val result = V2ConventionSeniorityPremiumStore.decodeConfirmed(
            "[${ruleJson("R1", "0292", source = "")}]"
        )

        assertFalse(result.reliable)
        assertTrue(result.rules.isEmpty())
    }

    private fun ruleJson(
        ruleId: String,
        idcc: String,
        rate: Double = 0.024,
        basis: String = "ACTUAL_MONTHLY_BASE",
        extensionStatus: String = "EXTENDED",
        source: String = "legifrance:KALI:KALIARTI000000000",
        steps: String = "[{\"years\":3,\"rate\":$rate,\"fixedMonthlyAmount\":null}]"
    ): String = """{
        "idcc":"$idcc",
        "ruleId":"$ruleId",
        "effectiveFrom":"2026-01-01",
        "effectiveTo":null,
        "classification":{"coefficient":700},
        "basis":"$basis",
        "steps":$steps,
        "includeConfirmedMonthlySupplement":false,
        "source":"$source",
        "extensionStatus":"$extensionStatus",
        "extensionEffectiveFrom":"2026-01-01"
    }""".trimIndent()
}
