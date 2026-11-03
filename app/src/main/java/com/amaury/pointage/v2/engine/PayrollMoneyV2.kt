package com.amaury.pointage.v2.engine

import java.math.BigDecimal
import java.math.RoundingMode

/** Decimal HALF_UP boundary shared with iOS; amounts inside payroll are signed cents. */
object PayrollMoneyV2 {
    // Representation limit, not a business limit. Every public conversion also checks its round trip.
    const val MAX_CENTS: Long = 9_007_199_254_740_991L
    private val limit = BigDecimal.valueOf(MAX_CENTS)

    fun cents(value: Double): Long? = decimal(value)?.movePointRight(2)?.let(::roundedCents)

    fun contribution(base: Double, rate: Double): Long? {
        if (base < 0.0 || rate < 0.0) return null
        val decimalBase = decimal(base) ?: return null
        val decimalRate = decimal(rate) ?: return null
        return roundedCents(decimalBase.multiply(decimalRate).movePointRight(2))
    }

    fun sum(values: Iterable<Long>): Long? {
        var result = 0L
        for (value in values) {
            if (!bounded(value)) return null
            // Both operands are bounded to 2^53-1, so the addition cannot overflow Long.
            result += value
            if (!bounded(result)) return null
        }
        return result.takeIf { amount(it) != null }
    }

    fun tax(baseCents: Long, rate: Double): Long? {
        if (!bounded(baseCents) || baseCents < 0L || !rate.isFinite() || rate !in 0.0..1.0) return null
        return roundedCents(BigDecimal.valueOf(baseCents).multiply(BigDecimal.valueOf(rate)))
    }

    fun amount(cents: Long): Double? {
        if (!bounded(cents)) return null
        val amount = BigDecimal.valueOf(cents, 2).toDouble()
        val recovered = BigDecimal.valueOf(amount).movePointRight(2).setScale(0, RoundingMode.HALF_UP)
        return amount.takeIf { recovered.compareTo(BigDecimal.valueOf(cents)) == 0 }
    }

    private fun decimal(value: Double): BigDecimal? =
        value.takeIf { it.isFinite() }?.let(BigDecimal::valueOf)

    private fun roundedCents(value: BigDecimal): Long? {
        val rounded = value.setScale(0, RoundingMode.HALF_UP)
        if (rounded.abs() > limit) return null
        val result = rounded.longValueExact()
        return result.takeIf { amount(it) != null }
    }

    private fun bounded(value: Long): Boolean = value in -MAX_CENTS..MAX_CENTS
}
