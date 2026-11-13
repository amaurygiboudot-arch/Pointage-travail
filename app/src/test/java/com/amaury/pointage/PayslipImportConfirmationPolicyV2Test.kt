package com.amaury.pointage

import org.junit.Assert.*
import org.junit.Test

class PayslipImportConfirmationPolicyV2Test {
    @Test fun `optional unknown is distinct from malformed amount`() {
        assertTrue(PayslipImportConfirmationPolicyV2.isOptionalAmountValid(""))
        assertNull(PayslipImportConfirmationPolicyV2.parseAmount(""))
        listOf("oops", "1,2,3", "NaN", "Infinity", "-1,00").forEach {
            assertFalse(it, PayslipImportConfirmationPolicyV2.isOptionalAmountValid(it))
        }
    }

    @Test fun `accepts French thousands separators and explicit zero`() {
        assertEquals(1234.56, PayslipImportConfirmationPolicyV2.parseAmount("1\u202f234,56")!!, 0.001)
        assertEquals(1234.56, PayslipImportConfirmationPolicyV2.parseAmount("1\u00a0234,56")!!, 0.001)
        assertEquals(0.0, PayslipImportConfirmationPolicyV2.parseAmount("0,00")!!, 0.001)
    }

    @Test fun `extreme finite amounts are invalid not unknown or saturated`() {
        for (raw in listOf("100000000000000000,00", "1e308", "90071992547409.92", "90071992547409.91", "1e2147483647", "1e-2147483647")) {
            assertNull(PayslipImportConfirmationPolicyV2.parseAmount(raw))
            assertFalse(PayslipImportConfirmationPolicyV2.isOptionalAmountValid(raw))
        }
        assertEquals(90_071_992_547_409.9, PayslipImportConfirmationPolicyV2.parseAmount("90071992547409.90")!!, 0.0)
        assertEquals(1.005, PayslipImportConfirmationPolicyV2.parseAmount("1,005")!!, 0.0)
    }

}
