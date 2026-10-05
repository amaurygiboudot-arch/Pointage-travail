package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.SegmentedSalaryCanonicalOutputV2
import com.amaury.pointage.v2.engine.PayslipDocumentParserV2

/**
 * Valeurs attendues pour la comparaison bulletin, lues uniquement depuis la sortie canonique segmentée.
 *
 * Aucun recalcul, aucun fallback legacy. Les champs non exposés par la sortie canonique
 * (paniers, mutuelle, prévoyance) restent simplement non comparés.
 */
object SegmentedPayslipComparisonValuesV2 {
    fun expected(output: SegmentedSalaryCanonicalOutputV2): Map<String, Double>? {
        if (!output.paidTimeReliable || !output.workedGrossReliable || !output.cashGrossReliable) return null

        val projection = output.net.projection
        val socialGross = projection
            ?.payroll
            ?.takeIf { output.net.cashGrossReliable && it.grossReliable }
            ?.gross
            ?.takeIf { it.isFinite() && it >= 0.0 }

        return linkedMapOf<String, Double>().apply {
            output.overtimeGross?.takeIf(::valid)?.let {
                put(PayslipDocumentParserV2.KEY_OVERTIME_GROSS, it)
            }
            output.premiumGross?.takeIf(::valid)?.let {
                put(PayslipDocumentParserV2.KEY_PREMIUMS_GROSS, it)
            }
            socialGross?.let {
                put(PayslipDocumentParserV2.KEY_GROSS, it)
            }
            if (output.netBeforeIncomeTaxComplete) {
                output.netBeforeIncomeTax?.takeIf(::valid)?.let {
                    put(PayslipDocumentParserV2.KEY_NET_BEFORE_TAX, it)
                }
                output.netTaxable?.takeIf(::valid)?.let {
                    put(PayslipDocumentParserV2.KEY_NET_TAXABLE, it)
                }
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun valid(value: Double): Boolean = value.isFinite() && value >= 0.0
}
