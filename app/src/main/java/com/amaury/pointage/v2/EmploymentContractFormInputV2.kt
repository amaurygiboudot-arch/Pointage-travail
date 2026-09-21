package com.amaury.pointage.v2

import com.amaury.pointage.v2.engine.EmploymentContractVersionInputV2
import com.amaury.pointage.v2.engine.EmploymentContractVersionInputValidatorV2
import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2
import com.amaury.pointage.v2.model.ForfaitHoursPeriodV2
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Frontière pure entre la fiche utilisateur et l'historique contractuel canonique.
 *
 * La date d'effet est obligatoire et reste distincte de la date d'entrée. Aucune date n'est
 * déduite de l'autre, du mois affiché ou de la date courante. Les nombres invalides restent
 * bloquants et aucune règle métier/convention/appareil n'est inventée ici.
 */
data class EmploymentContractFormInputV2(
    val employerId: String,
    val contractType: String,
    val weeklyHours: String,
    val forfaitAnnualHours: String,
    val forfaitAnnualDays: String,
    val monthlyGrossSalary: String,
    val grossHourlyRate: String,
    val hireDate: String,
    val effectiveDate: String,
    val sourceId: String
)

data class EmploymentContractFormBuildResultV2(
    val input: EmploymentContractVersionInputV2?,
    val warnings: List<String>
) {
    val ready: Boolean get() = input != null && warnings.isEmpty()
}

object EmploymentContractFormInputBuilderV2 {
    private val dateFormatter = DateTimeFormatter.ofPattern("dd/MM/uuuu")
        .withResolverStyle(ResolverStyle.STRICT)

    const val INVALID_TYPE_WARNING = "Contrat : choisissez un type de contrat précis."
    const val INVALID_HIRE_DATE_WARNING = "Contrat : date d'entrée invalide — JJ/MM/AAAA."
    const val INVALID_EFFECTIVE_DATE_WARNING = "Contrat : date d'effet invalide — JJ/MM/AAAA."
    const val INVALID_SOURCE_WARNING = "Contrat : indiquez la source qui confirme cette version."
    const val INVALID_NUMERIC_WARNING = "Contrat : les durées ou montants saisis sont invalides."

    fun build(
        form: EmploymentContractFormInputV2,
        checkedAtMs: Long,
        note: String? = null
    ): EmploymentContractFormBuildResultV2 {
        val employerId = form.employerId.trim()
        val type = when (form.contractType.trim().uppercase()) {
            "FULL_TIME" -> ContractTypeV2.FULL_TIME
            "PART_TIME" -> ContractTypeV2.PART_TIME
            "FORFAIT_HEURES", "FORFAIT_HOURS" -> ContractTypeV2.FORFAIT_HOURS
            "FORFAIT_JOURS", "FORFAIT_DAYS" -> ContractTypeV2.FORFAIT_DAYS
            "OTHER" -> ContractTypeV2.OTHER
            else -> null
        } ?: return EmploymentContractFormBuildResultV2(null, listOf(INVALID_TYPE_WARNING))

        val hireDate = parseDate(form.hireDate)
            ?: return EmploymentContractFormBuildResultV2(null, listOf(INVALID_HIRE_DATE_WARNING))
        val effectiveDate = parseDate(form.effectiveDate)
            ?: return EmploymentContractFormBuildResultV2(null, listOf(INVALID_EFFECTIVE_DATE_WARNING))
        val source = form.sourceId.trim()
        if (source.isEmpty()) {
            return EmploymentContractFormBuildResultV2(null, listOf(INVALID_SOURCE_WARNING))
        }

        val weeklyMinutes = when (type) {
            ContractTypeV2.FULL_TIME, ContractTypeV2.PART_TIME, ContractTypeV2.OTHER ->
                SalaryNumericInputV2.positiveMinutesFromHours(form.weeklyHours)
            else -> null
        }
        val hourlyRate = when (type) {
            ContractTypeV2.FULL_TIME, ContractTypeV2.PART_TIME, ContractTypeV2.OTHER ->
                SalaryNumericInputV2.positiveDecimal(form.grossHourlyRate)
            else -> null
        }
        val forfaitHours = if (type == ContractTypeV2.FORFAIT_HOURS)
            SalaryNumericInputV2.positiveDecimal(form.forfaitAnnualHours) else null
        val forfaitDays = if (type == ContractTypeV2.FORFAIT_DAYS)
            SalaryNumericInputV2.positiveDecimal(form.forfaitAnnualDays) else null
        val monthlyGross = if (type == ContractTypeV2.FORFAIT_HOURS || type == ContractTypeV2.FORFAIT_DAYS)
            SalaryNumericInputV2.positiveDecimal(form.monthlyGrossSalary) else null

        val requiredNumbersPresent = when (type) {
            ContractTypeV2.FULL_TIME, ContractTypeV2.PART_TIME, ContractTypeV2.OTHER ->
                weeklyMinutes != null && hourlyRate != null
            ContractTypeV2.FORFAIT_HOURS -> forfaitHours != null && monthlyGross != null
            ContractTypeV2.FORFAIT_DAYS -> forfaitDays != null && forfaitDays <= 218.0 && monthlyGross != null
            ContractTypeV2.FORFAIT -> false
        }
        if (!requiredNumbersPresent) {
            return EmploymentContractFormBuildResultV2(null, listOf(INVALID_NUMERIC_WARNING))
        }

        val contract = ContractV2(
            id = "contract_$employerId",
            employerId = employerId,
            type = type,
            contractualWeeklyMinutes = weeklyMinutes,
            grossHourlyRate = hourlyRate,
            hireDateEpochDay = hireDate.toEpochDay(),
            forfaitHoursPeriod = if (type == ContractTypeV2.FORFAIT_HOURS) ForfaitHoursPeriodV2.YEAR else null,
            forfaitHours = forfaitHours,
            forfaitAnnualDays = forfaitDays,
            monthlyGrossSalary = monthlyGross
        )
        val input = EmploymentContractVersionInputV2(
            contract = contract,
            effectiveFromEpochDay = effectiveDate.toEpochDay(),
            sourceId = source,
            checkedAtMs = checkedAtMs,
            note = note
        )
        val validation = EmploymentContractVersionInputValidatorV2.validate(input)
        return if (validation.ready) {
            EmploymentContractFormBuildResultV2(input, emptyList())
        } else {
            EmploymentContractFormBuildResultV2(null, validation.warnings)
        }
    }

    private fun parseDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.trim(), dateFormatter) }.getOrNull()
}
