package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.PayslipDocumentParserV2
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayslipObservedValuesStoreV2Test {
    private fun values(
        gross: Double = 2_000.0,
        netBeforeTax: Double = 1_600.0
    ) = JSONObject()
        .put(PayslipDocumentParserV2.KEY_GROSS, gross)
        .put(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX, netBeforeTax)

    @Test
    fun `stockage vide explicite est fiable`() {
        val result = PayslipObservedValuesStoreV2.decodeValues("{}")

        assertTrue(result.reliable)
        assertTrue(result.valuesByRecord.isEmpty())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `json vide illisible ou mauvais type est non fiable`() {
        val blank = PayslipObservedValuesStoreV2.decodeValues("   ")
        val malformed = PayslipObservedValuesStoreV2.decodeValues("not-json")
        val wrongRoot = PayslipObservedValuesStoreV2.decodeValues("[]")

        assertFalse(blank.reliable)
        assertFalse(malformed.reliable)
        assertFalse(wrongRoot.reliable)
    }

    @Test
    fun `une entree invalide contamine tout le stockage et bloque les valeurs partielles`() {
        val raw = JSONObject()
            .put("valid", values())
            .put("broken", "not-an-object")
            .toString()

        val stored = PayslipObservedValuesStoreV2.decodeValues(raw)

        assertFalse(stored.reliable)
        assertEquals(1, stored.valuesByRecord.size)
        assertTrue(PayslipObservedValuesStoreV2.comparisonValues(stored, "valid").isEmpty())
    }

    @Test
    fun `cle inconnue rend le stockage non fiable`() {
        val raw = JSONObject()
            .put("payslip-1", values().put("unknown_key", 12.0))
            .toString()

        val stored = PayslipObservedValuesStoreV2.decodeValues(raw)

        assertFalse(stored.reliable)
        assertTrue(PayslipObservedValuesStoreV2.comparisonValues(stored, "payslip-1").isEmpty())
    }

    @Test
    fun `montant texte ou negatif rend le stockage non fiable`() {
        val stringAmount = JSONObject()
            .put("payslip-1", values().put(PayslipDocumentParserV2.KEY_GROSS, "2000,00"))
            .toString()
        val negativeAmount = JSONObject()
            .put("payslip-1", values().put(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX, -1.0))
            .toString()

        assertFalse(PayslipObservedValuesStoreV2.decodeValues(stringAmount).reliable)
        assertFalse(PayslipObservedValuesStoreV2.decodeValues(negativeAmount).reliable)
    }

    @Test
    fun `objet de valeurs vide est incoherent`() {
        val raw = JSONObject().put("payslip-1", JSONObject()).toString()

        val stored = PayslipObservedValuesStoreV2.decodeValues(raw)

        assertFalse(stored.reliable)
        assertTrue(stored.valuesByRecord.isEmpty())
    }

    @Test
    fun `valeurs valides restent disponibles et seules les cles comparables sont exposees`() {
        val item = values()
            .put(PayslipDocumentParserV2.KEY_PREMIUMS_GROSS, 120.0)
            .put(PayslipDocumentParserV2.KEY_COMPLEMENTARY_RETIREMENT_EMPLOYEE, 85.0)
        val stored = PayslipObservedValuesStoreV2.decodeValues(
            JSONObject().put("payslip-1", item).toString()
        )
        val comparable = PayslipObservedValuesStoreV2.comparisonValues(stored, "payslip-1")

        assertTrue(stored.reliable)
        assertEquals(4, stored.valuesByRecord.getValue("payslip-1").size)
        assertEquals(2, comparable.size)
        assertTrue(PayslipDocumentParserV2.KEY_GROSS in comparable)
        assertTrue(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX in comparable)
        assertFalse(PayslipDocumentParserV2.KEY_PREMIUMS_GROSS in comparable)
        assertFalse(PayslipDocumentParserV2.KEY_COMPLEMENTARY_RETIREMENT_EMPLOYEE in comparable)
    }
}
