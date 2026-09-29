package com.amaury.pointage.v2.engine

import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class CompanyPayrollDatedContractInputsV2(
    val idcc: String?,
    val entryDate: LocalDate?,
    val seniorityMonths: Int?,
    val contractType: ContractTypeV2?,
    val contractualWeeklyMinutes: Int?,
    val forfaitAnnualDays: Double?
)

internal fun resolveCompanyPayrollDatedContractInputsV2(
    company: SalaryCompanyStore.Company,
    contract: ContractV2?,
    referenceDate: LocalDate
): CompanyPayrollDatedContractInputsV2 {
    val idcc = company.idcc.filter(Char::isDigit).trimStart('0').ifBlank { null }
    val entryDate = contract?.hireDateEpochDay?.let { epochDay ->
        runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull()
    }
    val seniorityMonths = entryDate?.let { start ->
        if (start.isAfter(referenceDate)) 0
        else ChronoUnit.MONTHS.between(start, referenceDate).toInt().coerceAtLeast(0)
    }
    return CompanyPayrollDatedContractInputsV2(
        idcc = idcc,
        entryDate = entryDate,
        seniorityMonths = seniorityMonths,
        contractType = contract?.type,
        contractualWeeklyMinutes = contract?.contractualWeeklyMinutes,
        forfaitAnnualDays = contract?.forfaitAnnualDays
    )
}
