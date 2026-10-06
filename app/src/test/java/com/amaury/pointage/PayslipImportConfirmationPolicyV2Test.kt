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
}
