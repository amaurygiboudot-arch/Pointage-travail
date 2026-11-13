package com.amaury.pointage.v2.engine

import java.util.Locale

/** Evidence carried out of the existing calculation, never reconstructed from a payslip or total. */
data class CalculatedContributionLineV2(
    val id: String,
    val label: String,
    val family: EmployerContributionProjectionV2.Family,
    val base: Double,
    val employeeRate: Double?,
    val employeeAmount: Double?,
    val employerRate: Double?,
    val employerAmount: Double?,
    val source: String
)

data class CalculatedEmployeeDeductionV2(val id: String, val label: String, val amount: Double?)

/** One presentation of known lines for the screen and PDF. Does not calculate contributions. */
object CalculatedPayslipDetailsV2 {
    const val UNKNOWN = "À confirmer"
    data class Row(val label: String, val base: String, val employeeRate: String,
                   val employeeAmount: String, val employerRate: String, val employerAmount: String,
                   val source: String) {
        fun cells() = listOf(label, base, employeeRate, employeeAmount, employerRate, employerAmount)
        fun text() = "$label\nBase : $base • Salarié : $employeeRate / $employeeAmount • Employeur : $employerRate / $employerAmount\nSource : $source"
    }

    fun contributions(payroll: NetSalaryEngineV2.Result?, grossReliable: Boolean,
                      employer: EmployerContributionProjectionV2.Result?,
                      companyId: String, year: Int, month: Int): List<Row> {
        if (!grossReliable || payroll?.grossReliable != true) return emptyList()
        val context = payroll.employerProjection?.context ?: return emptyList()
        if (!context.valid || context.companyId != companyId || context.year != year || context.month != month ||
            context.contributionGrossCents != PayrollMoneyV2.cents(payroll.gross)) return emptyList()
        val employeeReady = payroll.complete && payroll.employeeAmountsReliable
        val employerReady = employer?.takeIf { it == payroll.employerProjection && it.context.valid &&
            it.context.contributionGrossCents == PayrollMoneyV2.cents(payroll.gross) }
        val lines = payroll.contributionDetails
        if (lines.map { it.id }.distinct().size != lines.size) return emptyList()
        return lines.mapNotNull { line ->
            if (line.id.isBlank() || line.label.isBlank() || line.source.isBlank() || money(line.base) == null) return@mapNotNull null
            val familyLines = lines.filter { it.family == line.family }
            fun total(selector: (CalculatedContributionLineV2) -> Double?): Long? {
                val values = familyLines.map { selector(it)?.takeIf { v -> v.isFinite() && v >= 0.0 }?.let(PayrollMoneyV2::cents) }
                return if (values.any { it == null }) null else PayrollMoneyV2.sum(values.filterNotNull())
            }
            val employeeFamilyAmount = when (line.family) {
                EmployerContributionProjectionV2.Family.STATUTORY -> payroll.statutory
                EmployerContributionProjectionV2.Family.COMPLEMENTARY_RETIREMENT -> payroll.complementaryRetirement
                else -> null
            }
            val employeeFamilyReady = employeeReady && employeeFamilyAmount?.let(PayrollMoneyV2::cents)?.let {
                it == total { row -> row.employeeAmount }
            } == true
            val family = employerReady?.lines?.singleOrNull { it.family == line.family }
            val employerFamilyKnown = family?.state == EmployerContributionProjectionV2.State.KNOWN &&
                family.amountCents != null && family.amountCents == total { it.employerAmount }
            Row(line.label, money(line.base)!!,
                if (employeeFamilyReady) line.employeeRate?.let(::rate) ?: UNKNOWN else UNKNOWN,
                if (employeeFamilyReady) line.employeeAmount?.let(::money) ?: UNKNOWN else UNKNOWN,
                if (employerFamilyKnown) line.employerRate?.let(::rate) ?: UNKNOWN else UNKNOWN,
                if (employerFamilyKnown) line.employerAmount?.let(::money) ?: UNKNOWN else UNKNOWN,
                line.source)
        }
    }

    fun deductions(payroll: NetSalaryEngineV2.Result?, grossReliable: Boolean,
                   companyId: String, year: Int, month: Int): List<Pair<String, String>> {
        val unavailable = listOf("Détail des retenues salariales" to UNKNOWN)
        if (!grossReliable || payroll?.grossReliable != true || !payroll.complete || !payroll.employeeAmountsReliable) return unavailable
        val context = payroll.employerProjection?.context ?: return unavailable
        if (!context.valid || context.companyId != companyId || context.year != year || context.month != month || context.contributionGrossCents != PayrollMoneyV2.cents(payroll.gross)) return unavailable
        val lines = payroll.employeeDeductionDetails
        if (lines.isEmpty() || lines.any { it.id.isBlank() || it.label.isBlank() } || lines.map { it.id }.distinct().size != lines.size) return unavailable
        val cents = lines.map { it.amount?.takeIf { amount -> amount.isFinite() && amount >= 0.0 }?.let(PayrollMoneyV2::cents) }
        if (cents.any { it == null } || PayrollMoneyV2.sum(cents.filterNotNull()) != PayrollMoneyV2.cents(payroll.companyEmployeeDeductions)) return unavailable
        return lines.map { it.label to (it.amount?.let(::money) ?: UNKNOWN) } +
            listOf("Sources des retenues" to "Montants utilisés par le calcul ; références individuelles, bases et taux non exposés. Ces lignes détaillent les retenues et ne s’ajoutent pas aux totaux.")
    }

    fun earnings(canonical: SegmentedSalaryCanonicalOutputV2?): List<Pair<String, String>> = buildList {
        fun value(amount: Double?) = amount?.takeIf { canonical?.workedGrossReliable == true }?.let(::money) ?: UNKNOWN
        add("Salaire de base calculé" to value(canonical?.baseGross))
        add("Heures supplémentaires — montant brut" to value(canonical?.overtimeGross))
        add("Heures complémentaires — montant brut" to value(canonical?.complementaryGross))
        add("Majorations variables — montant brut" to value(canonical?.premiumGross))
        addAll(fixedLines(canonical))
    }

    fun earnings(salary: com.amaury.pointage.V2SalaryAdapter.Result?): List<Pair<String, String>> = buildList {
        val ready = salary?.monthlyGrossReliable == true && salary.paidTimeReliable
        fun value(amount: Double?) = amount?.takeIf { ready }?.let(::money) ?: UNKNOWN
        add("Salaire de base calculé" to value(salary?.regularGross))
        add("Heures supplémentaires / complémentaires — montant brut" to value(salary?.overtimeGross))
        add("Primes et majorations — total brut" to value(salary?.premiumsGross))
        add("Dont prime d’ancienneté" to value(salary?.seniorityPremiumGross))
        val details = salary?.companyPremiumDetails.orEmpty()
        val amounts = details.map { it.grossAmount.takeIf { amount -> amount.isFinite() && amount >= 0.0 }?.let(PayrollMoneyV2::cents) }
        val sum = if (amounts.any { it == null }) null else PayrollMoneyV2.sum(amounts.filterNotNull())
        val detailReady = details.isNotEmpty() && details.all { it.id.isNotBlank() && it.label.isNotBlank() } &&
            details.map { it.id }.distinct().size == details.size && sum != null &&
            salary?.premiumsGross?.let(PayrollMoneyV2::cents)?.let { sum <= it } == true
        if (detailReady) details.forEach { add("Dont ${it.label}" to value(it.grossAmount)) }
        else add("Détail des primes d’entreprise" to UNKNOWN)
    }

    fun settlementLines(source: com.amaury.pointage.v2.ConfirmedMonthlyPayrollSettlementV2.Result?,
                        companyId: String, period: String, basis: MonthlyPayrollSettlementProjectionV2.NetBasis?): List<Pair<String, String>> =
        MonthlyPayrollSettlementProjectionV2.lines(source, companyId, period, basis)

    /** Legacy callers without a selected monthly source retain explicitly unknown amounts. */
    fun settlementLines(): List<Pair<String, String>> = listOf(
        "Prime / remboursement de transport" to "$UNKNOWN — montant distinct des primes brutes",
        "Saisie-arrêt / autres retenues sur versement" to "$UNKNOWN — hors cotisations",
        "Montant net social" to "$UNKNOWN — assiette dédiée non disponible",
        "Net à payer après frais et retenues" to "$UNKNOWN — distinct du net estimé après PAS"
    )

    fun fixedLines(canonical: SegmentedSalaryCanonicalOutputV2?): List<Pair<String, String>> =
        fixedLines(canonical?.cash, canonical?.cashGrossReliable == true)

    internal fun fixedLines(cash: SegmentedCashGrossAssemblyResultV2?, reliable: Boolean): List<Pair<String, String>> {
        if (!reliable || cash?.reliable != true) return listOf("Primes fixes et ancienneté" to UNKNOWN)
        val components = cash.confirmedComponents
        val cents = components.map { it.amount.takeIf { amount -> amount.isFinite() && amount >= 0.0 }?.let(PayrollMoneyV2::cents) }
        val nativeTotal = components.sumOf { it.amount }.takeIf { it.isFinite() && it >= 0.0 }?.let(PayrollMoneyV2::cents)
        val recordedTotal = cash.additionalCashGross?.let(PayrollMoneyV2::cents)
        val displayedTotal = if (cents.any { it == null }) null else PayrollMoneyV2.sum(cents.filterNotNull())
        if (components.any { !it.reliable || it.id.isBlank() || it.label.isBlank() } ||
            components.map { it.id }.distinct().size != components.size || nativeTotal == null ||
            displayedTotal == null || nativeTotal != recordedTotal)
            return listOf("Primes fixes et ancienneté" to UNKNOWN)
        val rows = components.map { component ->
            component.label to (money(component.amount)?.takeIf { component.reliable } ?: UNKNOWN)
        }.ifEmpty { listOf("Détail des primes fixes et ancienneté" to UNKNOWN) }
        val difference = runCatching { Math.subtractExact(nativeTotal, displayedTotal) }.getOrNull()
            ?: return listOf("Primes fixes et ancienneté" to UNKNOWN)
        return if (difference == 0L) rows else rows + ("Écart d’arrondi de présentation des primes" to
            String.format(Locale.FRANCE,"%+.2f € — total du calcul conservé",PayrollMoneyV2.amount(difference)))

    }

    fun money(value: Double): String? = value.takeIf { it.isFinite() && it >= 0.0 }
        ?.let(PayrollMoneyV2::cents)?.let { String.format(Locale.FRANCE, "%.2f €", PayrollMoneyV2.amount(it)) }
    private fun rate(value: Double): String? = value.takeIf { it.isFinite() && it in 0.0..1.0 }
        ?.let { String.format(Locale.FRANCE, "%.4f %%", it * 100.0) }
}
