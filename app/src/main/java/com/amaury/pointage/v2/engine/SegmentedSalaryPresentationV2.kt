package com.amaury.pointage.v2.engine

object SegmentedSalaryPresentationV2 {
    enum class State {
        UNRELIABLE,
        GROSS_AVAILABLE_NET_INCOMPLETE,
        AVAILABLE
    }

    data class Result(
        val state: State,
        val workedGross: Double?,
        val additionalCashGross: Double?,
        val cashGross: Double?,
        val socialGross: Double?,
        val netBeforeIncomeTax: Double?,
        val netTaxable: Double?,
        val incomeTax: Double?,
        val netAfterIncomeTax: Double?,
        val contributingSessionCount: Int?,
        val warnings: List<String>
    )

    fun from(source: SegmentedSalaryProductionResultV2): Result {
        val warnings = source.warnings.distinct()
        if (!source.worked.reliable || !source.cash.reliable || !source.net.cashGrossReliable) {
            return Result(
                state = State.UNRELIABLE,
                workedGross = null,
                additionalCashGross = null,
                cashGross = null,
                socialGross = null,
                netBeforeIncomeTax = null,
                netTaxable = null,
                incomeTax = null,
                netAfterIncomeTax = null,
                contributingSessionCount = null,
                warnings = warnings
            )
        }

        val projection = source.net.projection
        val workedGross = source.cash.workedGross
        val cashGross = source.cash.cashGross
        if (projection == null || !source.net.netBeforeIncomeTaxComplete) {
            return Result(
                state = State.GROSS_AVAILABLE_NET_INCOMPLETE,
                workedGross = workedGross,
                additionalCashGross = source.cash.additionalCashGross,
                cashGross = cashGross,
                socialGross = projection?.payroll?.gross?.takeIf { projection.payroll.grossReliable },
                netBeforeIncomeTax = null,
                netTaxable = null,
                incomeTax = null,
                netAfterIncomeTax = null,
                contributingSessionCount = source.worked.evidence.contributingSessionIds.size,
                warnings = warnings
            )
        }

        return Result(
            state = State.AVAILABLE,
            workedGross = workedGross,
            additionalCashGross = source.cash.additionalCashGross,
            cashGross = cashGross,
            socialGross = projection.payroll.gross.takeIf { projection.payroll.grossReliable },
            netBeforeIncomeTax = projection.netBeforeIncomeTax,
            netTaxable = projection.netTaxable,
            incomeTax = projection.incomeTax,
            netAfterIncomeTax = projection.netAfterIncomeTax,
            contributingSessionCount = source.worked.evidence.contributingSessionIds.size,
            warnings = warnings
        )
    }
}
