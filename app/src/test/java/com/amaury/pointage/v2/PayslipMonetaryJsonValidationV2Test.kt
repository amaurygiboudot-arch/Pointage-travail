package com.amaury.pointage.v2

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PayslipMonetaryJsonValidationV2Test {
    @Test fun frameworkReaderChecksOriginalDecimalForAllStoredMoneyRoutes() {
        for (token in listOf("90071992547409.91", "90071992547409.92", "1e17", "1e308", "1e2147483647", "-0.001")) {
            assertFalse(token, PayslipMonetaryJsonValidationV2.records("[{\"gross\":$token}]"))
            assertFalse(token, PayslipMonetaryJsonValidationV2.observed("{\"old\":{\"Brut\":$token}}"))
            for (fields in listOf("\"proposedValues\":{\"Brut\":$token}",
                "\"originalValues\":{\"Brut\":$token}", "\"originalRecord\":{\"net\":$token}")) {
                assertFalse(PayslipMonetaryJsonValidationV2.drafts("{\"draft\":{$fields}}"))
                assertFalse(PayslipMonetaryJsonValidationV2.singleDraft("{$fields}"))
            }
        }
        for (token in listOf("0", "-0", "2500.02", "1.005", "90071992547409.90")) {
            assertTrue(token, PayslipMonetaryJsonValidationV2.records("[{\"gross\":$token,\"net\":null}]"))
            assertTrue(token, PayslipMonetaryJsonValidationV2.observed("{\"old\":{\"Brut\":$token}}"))
        }
    }

    @Test fun invalidDraftTextAndNumericLookingIdentifiersAreNotMonetaryTokens() {
        val raw = """{"1e17":{"schema":1,"year":2026,"createdAtMs":1234567890,
            "rawFields":{"Brut":"100000000000000000,00"},
            "proposedEvidence":{"Brut":"Example 1e17 and \"quoted\" text"},
            "proposedValues":{"Brut":2000},"originalRecord":null,"originalValues":null}}"""
        assertTrue(PayslipMonetaryJsonValidationV2.drafts(raw))
        assertTrue(PayslipMonetaryJsonValidationV2.observed("{\"123\\\"456\":{\"Brut\":2000}}"))
        assertFalse(PayslipMonetaryJsonValidationV2.observed("{\"old\":{\"Brut\":\"2000\"}}"))
        assertFalse(PayslipMonetaryJsonValidationV2.observed("{\"old\":{\"Brut\":null}}"))
        assertFalse(PayslipMonetaryJsonValidationV2.records("[] []"))
    }
}
