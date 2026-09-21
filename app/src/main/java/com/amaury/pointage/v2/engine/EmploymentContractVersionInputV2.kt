package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import com.amaury.pointage.v2.model.ContractV2

/**
 * Entrée canonique d'une version contractuelle datée.
 *
 * La date d'effet est volontairement séparée de la date d'embauche portée par [ContractV2].
 * Aucune des deux dates n'est déduite de l'autre.
 */
data class EmploymentContractVersionInputV2(
    val contract: ContractV2,
    val effectiveFromEpochDay: Long,
    val sourceId: String,
    val checkedAtMs: Long,
    val note: String? = null
)

data class EmploymentContractVersionInputValidationV2(
    val ready: Boolean,
    val warnings: List<String>
)

object EmploymentContractVersionInputValidatorV2 {
    const val INVALID_ID_WARNING =
        "Contrat : identifiant de contrat ou d'employeur manquant."
    const val INVALID_SOURCE_WARNING =
        "Contrat : la source de confirmation est obligatoire."
    const val EFFECT_BEFORE_HIRE_WARNING =
        "Contrat : la date d'effet confirmée ne peut pas précéder la date d'entrée dans l'entreprise."
    const val LEGACY_FORFAIT_WARNING =
        "Contrat : l'ancien type forfait générique doit être précisé en forfait heures ou forfait jours."
    const val INVALID_TERMS_WARNING =
        "Contrat : les paramètres contractuels confirmés sont incomplets ou incohérents."

    fun validate(input: EmploymentContractVersionInputV2): EmploymentContractVersionInputValidationV2 {
        val warnings = mutableListOf<String>()
        val contract = input.contract

        if (contract.id.trim().isEmpty() || contract.employerId.trim().isEmpty()) {
            warnings += INVALID_ID_WARNING
        }
        if (input.sourceId.trim().isEmpty()) {
            warnings += INVALID_SOURCE_WARNING
        }
        contract.hireDateEpochDay?.let { hireDate ->
            if (input.effectiveFromEpochDay < hireDate) warnings += EFFECT_BEFORE_HIRE_WARNING
        }
        if (contract.type == ContractTypeV2.FORFAIT) {
            warnings += LEGACY_FORFAIT_WARNING
        } else if (!validTerms(contract)) {
            warnings += INVALID_TERMS_WARNING
        }

        return EmploymentContractVersionInputValidationV2(
            ready = warnings.isEmpty(),
            warnings = warnings.distinct()
        )
    }

    private fun validTerms(contract: ContractV2): Boolean {
        if (contract.payrollCutoffDay?.let { it !in 1..31 } == true) return false

        return when (contract.type) {
            ContractTypeV2.FULL_TIME,
            ContractTypeV2.PART_TIME,
            ContractTypeV2.OTHER -> {
                val weekly = contract.contractualWeeklyMinutes
                val rate = contract.grossHourlyRate
                weekly != null && weekly > 0 &&
                    rate != null && rate.isFinite() && rate > 0.0 &&
                    contract.forfaitHoursPeriod == null &&
                    contract.forfaitHours == null &&
                    contract.forfaitAnnualDays == null &&
                    contract.monthlyGrossSalary == null
            }

            ContractTypeV2.FORFAIT_HOURS -> {
                val monthly = contract.monthlyGrossSalary
                val hours = contract.forfaitHours
                contract.contractualWeeklyMinutes == null &&
                    contract.grossHourlyRate == null &&
                    monthly != null && monthly.isFinite() && monthly > 0.0 &&
                    contract.forfaitHoursPeriod != null &&
                    hours != null && hours.isFinite() && hours > 0.0 &&
                    contract.forfaitAnnualDays == null
            }

            ContractTypeV2.FORFAIT_DAYS -> {
                val monthly = contract.monthlyGrossSalary
                val days = contract.forfaitAnnualDays
                contract.contractualWeeklyMinutes == null &&
                    contract.grossHourlyRate == null &&
                    monthly != null && monthly.isFinite() && monthly > 0.0 &&
                    contract.forfaitHoursPeriod == null &&
                    contract.forfaitHours == null &&
                    days != null && days.isFinite() && days > 0.0 && days <= 218.0
            }

            ContractTypeV2.FORFAIT -> false
        }
    }
}
