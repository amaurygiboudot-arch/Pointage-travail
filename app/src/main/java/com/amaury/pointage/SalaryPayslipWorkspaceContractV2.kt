package com.amaury.pointage

import com.amaury.pointage.v2.model.ContractV2
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal data class SalaryPayslipWorkspaceContractPresentationV2(
    val conventionId: String?,
    val weeklyLabel: String,
    val seniorityLabel: String
)

internal fun salaryPayslipWorkspaceContractPresentationV2(
    company: SalaryCompanyStore.Company,
    contract: ContractV2?,
    year: Int,
    monthZeroBased: Int
): SalaryPayslipWorkspaceContractPresentationV2 {
    require(monthZeroBased in 0..11) { "Mois invalide" }

    val conventionId = company.idcc.trim().takeIf { it.isNotBlank() }
    val weeklyLabel = contract?.contractualWeeklyMinutes
        ?.takeIf { it >= 0 }
        ?.let { minutes -> "%dh%02d".format(minutes / 60, minutes % 60) }
        ?: "à confirmer"

    val seniorityLabel = contract?.hireDateEpochDay?.let { epochDay ->
        val start = runCatching { LocalDate.ofEpochDay(epochDay) }.getOrNull()
            ?: return@let "à confirmer"
        val firstDay = LocalDate.of(year, monthZeroBased + 1, 1)
        val end = firstDay.withDayOfMonth(firstDay.lengthOfMonth())
        if (start.isAfter(end)) {
            "0 mois"
        } else {
            val months = ChronoUnit.MONTHS.between(
                start.withDayOfMonth(1),
                end.withDayOfMonth(1)
            ).toInt().coerceAtLeast(0)
            val years = months / 12
            val remainder = months % 12
            when {
                years > 0 && remainder > 0 ->
                    years.toString() + " an" + (if (years > 1) "s" else "") + " et " + remainder + " mois"
                years > 0 -> years.toString() + " an" + (if (years > 1) "s" else "")
                else -> remainder.toString() + " mois"
            }
        }
    } ?: "à confirmer"

    return SalaryPayslipWorkspaceContractPresentationV2(
        conventionId = conventionId,
        weeklyLabel = weeklyLabel,
        seniorityLabel = seniorityLabel
    )
}
