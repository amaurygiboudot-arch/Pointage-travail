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

    @Test
    fun `net imposable reste bloque sans reference nette principale`() {
        assertNull(
            NetSalaryReferencePolicyV2.taxable(
                2_150.0,
                beforeIncomeTaxReferenceAvailable = false
            )
        )
    }

    @Test
    fun `net imposable fini peut devenir une reference apres validation du net principal`() {
        assertEquals(
            2_150.0,
            NetSalaryReferencePolicyV2.taxable(
                2_150.0,
                beforeIncomeTaxReferenceAvailable = true
            )!!,
            0.001
        )
    }

    @Test
    fun `net imposable invalide reste bloque`() {
        assertNull(NetSalaryReferencePolicyV2.taxable(null, beforeIncomeTaxReferenceAvailable = true))
        assertNull(NetSalaryReferencePolicyV2.taxable(Double.NaN, beforeIncomeTaxReferenceAvailable = true))
        assertNull(NetSalaryReferencePolicyV2.taxable(-1.0, beforeIncomeTaxReferenceAvailable = true))
    }
}
