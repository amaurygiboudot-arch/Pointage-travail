package com.amaury.pointage.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetSalaryReferencePolicyV2Test {
    @Test
    fun `net incomplet ne devient jamais une reference`() {
        assertNull(NetSalaryReferencePolicyV2.beforeIncomeTax(2_000.0, complete = false))
    }

    @Test
    fun `net complet et fini peut devenir une reference`() {
        assertEquals(
            2_000.0,
            NetSalaryReferencePolicyV2.beforeIncomeTax(2_000.0, complete = true)!!,
            0.001
        )
    }

    @Test
    fun `valeur invalide reste bloquee meme si complete`() {
        assertNull(NetSalaryReferencePolicyV2.beforeIncomeTax(Double.NaN, complete = true))
        assertNull(NetSalaryReferencePolicyV2.beforeIncomeTax(-1.0, complete = true))
    }
}
