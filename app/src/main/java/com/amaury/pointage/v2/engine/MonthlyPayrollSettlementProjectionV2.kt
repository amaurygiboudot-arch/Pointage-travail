package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.ConfirmedMonthlyPayrollSettlementV2
import java.time.YearMonth
import java.util.Locale

/** Observed facts stay separate from the estimate, including when payroll is unavailable. */
object MonthlyPayrollSettlementProjectionV2 {
    data class NetBasis(val companyId: String, val period: String, val netAfterTaxCents: Long)
    data class Result(
        val record: ConfirmedMonthlyPayrollSettlementV2.Record?,
        val estimatedPaidCents: Long?, val observedMinusEstimateCents: Long?, val warning: String?
    )

    /** A base must come from the reliable upstream net projection, never the engine's raw subtotal. */
    fun netBasis(payroll: NetSalaryEngineV2.Result?, reliableNetAfterTax: Double?, companyId: String, period: String): NetBasis? {
        val cents = reliableNetAfterTax?.takeIf { it.isFinite() && it >= 0.0 }?.let(PayrollMoneyV2::cents) ?: return null
        if (payroll?.settlementBaseReliable != true || !payroll.grossReliable || !payroll.employeeAmountsReliable ||
            payroll.netAfterIncomeTax?.let(PayrollMoneyV2::cents) != cents) return null
        val date = runCatching { YearMonth.parse(period) }.getOrNull() ?: return null
        val context = payroll.employerProjection?.context ?: return null
        if (!context.valid || context.companyId != companyId || context.year != date.year || context.month != date.monthValue ||
            context.contributionGrossCents != PayrollMoneyV2.cents(payroll.gross)) return null
        return NetBasis(companyId, period, cents)
    }

    fun project(source: ConfirmedMonthlyPayrollSettlementV2.Result?, companyId: String, period: String, basis: NetBasis?): Result {
        val record = source?.record?.takeIf { source.reliable && ConfirmedMonthlyPayrollSettlementV2.valid(it) &&
            it.companyId == companyId && it.period == period }
        if (record == null) return Result(null, null, null, if (source?.reliable == false || source?.record != null)
            ConfirmedMonthlyPayrollSettlementV2.WARNING else null)
        val trustedBasis = basis?.takeIf { it.companyId == companyId && it.period == period && it.netAfterTaxCents >= 0 &&
            PayrollMoneyV2.amount(it.netAfterTaxCents) != null }
        if (!record.adjustmentsComplete || trustedBasis == null) return Result(record, null, null,
            "Estimation du versement à confirmer : net après PAS fiable sans écrêtement et quatre ajustements exhaustifs confirmés nécessaires.")
        val amounts = record.adjustments().map { it?.let(PayrollMoneyV2::cents) }
        if (amounts.any { it == null }) return Result(record, null, null, "Ajustements mensuels incomplets.")
        val paid = PayrollMoneyV2.sum(listOf(trustedBasis.netAfterTaxCents, amounts[0]!!, amounts[2]!!, -amounts[1]!!, -amounts[3]!!))
            ?.takeIf { it >= 0L }
        if (paid == null) return Result(record, null, null, "Versement estimé négatif ou non représentable au centime : régularisation à confirmer.")
        val difference = record.netPaidObserved?.let(PayrollMoneyV2::cents)?.let { PayrollMoneyV2.sum(listOf(it, -paid)) }
        return Result(record, paid, difference, null)
    }

    fun lines(source: ConfirmedMonthlyPayrollSettlementV2.Result?, companyId: String, period: String, basis: NetBasis?): List<Pair<String, String>> {
        val result = project(source, companyId, period, basis)
        val record = result.record
        fun observed(value: Double?) = value?.let(CalculatedPayslipDetailsV2::money)?.plus(" — constaté sur la source du mois") ?: "À confirmer"
        return buildList {
            add("Prime / remboursement de transport hors brut" to observed(record?.transportReimbursement))
            add("Saisie-arrêt sur versement" to observed(record?.garnishment))
            add("Autres ajouts hors brut (dont paniers et frais non déjà inclus)" to observed(record?.otherAddition))
            add("Autres retenues sur versement, hors cotisations et PAS" to observed(record?.otherDeduction))
            add("Montant net social constaté" to observed(record?.netSocialObserved))
            add("Net réellement payé constaté" to observed(record?.netPaidObserved))
            add("Net à payer estimé après ajustements confirmés" to (result.estimatedPaidCents?.let(::money) ?: "À confirmer — distinct du net constaté"))
            result.observedMinusEstimateCents?.let { add("Écart net constaté − estimation" to money(it)) }
            if (record != null) {
                add("Source mensuelle — $period" to record.source)
                add("Ajustements après net employeur après PAS" to if (record.adjustmentsComplete)
                    "Exhaustivité confirmée : quatre ajustements hors brut, cotisations et PAS, non déjà intégrés. Les montants constatés ne modifient pas le moteur de paie."
                    else "Exhaustivité non confirmée ; aucune estimation du versement produite.")
            }
            result.warning?.let { add("À vérifier" to it) }
            add("Lecture" to "Le net social et le net payé constatés restent des références du mois, pas des résultats calculés. IJSS séparées ; retenue transport salariale et avantages en nature jamais déduits une seconde fois.")
        }
    }

    private fun money(cents: Long): String = String.format(Locale.FRANCE, "%.2f €", requireNotNull(PayrollMoneyV2.amount(cents)))
}
