package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.v2.engine.SegmentedMonthlyBaseBridgeV2
import com.amaury.pointage.v2.engine.SegmentedMonthlyBaseResultV2

object V2SegmentedMonthlyBaseProduction {
    fun resolve(
        context: Context,
        companyId: String,
        year: Int,
        monthZeroBased: Int,
        contractSnapshot: V2EmploymentContractPayrollBridge.Snapshot?,
        conventionSnapshot: V2ConventionRulePayrollBridge.Snapshot?
    ): SegmentedMonthlyBaseResultV2? {
        val contracts = contractSnapshot?.resolution ?: return null
        if (contracts.calculationSegments.size <= 1 ||
            !contracts.requiresMultipleContractVersions
        ) return null
        val rules = conventionSnapshot?.resolution ?: return null

        val source = V2SegmentedProrationStore.resolve(
            context = context,
            companyId = companyId,
            year = year,
            monthZeroBased = monthZeroBased
        )
        return SegmentedMonthlyBaseBridgeV2.calculate(
            contracts = contracts,
            rules = rules,
            prorationSource = source
        )
    }
}
