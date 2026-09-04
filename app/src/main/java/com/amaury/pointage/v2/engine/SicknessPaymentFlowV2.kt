package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.AbsenceSalaryTreatmentV2
import com.amaury.pointage.v2.model.AbsenceSubrogationV2
import com.amaury.pointage.v2.model.AbsenceV2

/**
 * Décrit le circuit de paiement d'un arrêt maladie sans additionner des montants
 * qui n'ont pas la même nature sociale/fiscale.
 *
 * - Sans subrogation : les IJSS estimées sont versées directement au salarié.
 * - Avec subrogation : les IJSS estimées sont versées à l'employeur ; elles ne
 *   doivent donc jamais être ajoutées une seconde fois au montant versé par lui.
 * - Le maintien employeur reste un élément distinct tant que sa règle/montant exact
 *   n'est pas connu.
 */
object SicknessPaymentFlowV2 {
    enum class IjssRecipient { EMPLOYEE, EMPLOYER, TO_CONFIRM }

    data class Result(
        val ijssRecipient: IjssRecipient,
        val directEmployeeIjssGross: Double?,
        val employerIjssReimbursementGross: Double?,
        val salaryMaintenanceKnown: Boolean,
        val doubleCountSafe: Boolean,
        val warnings: List<String>
    )

    fun resolve(absence: AbsenceV2, allowance: SicknessDailyAllowanceV2.Result?): Result {
        val warnings = mutableListOf<String>()
        val ijss = allowance?.estimatedGrossTotal?.takeIf { allowance.complete }

        val recipient = when (absence.subrogation) {
            AbsenceSubrogationV2.YES -> IjssRecipient.EMPLOYER
            AbsenceSubrogationV2.NO -> IjssRecipient.EMPLOYEE
            AbsenceSubrogationV2.TO_CONFIRM -> IjssRecipient.TO_CONFIRM
        }

        if (absence.subrogation == AbsenceSubrogationV2.YES && absence.salaryTreatment == AbsenceSalaryTreatmentV2.UNPAID) {
            warnings += "Subrogation déclarée alors qu'aucun maintien employeur n'est déclaré : combinaison à vérifier."
        }
        if (absence.subrogation == AbsenceSubrogationV2.TO_CONFIRM) {
            warnings += "Subrogation à confirmer : HoraTrack ne sait pas encore si les IJSS vont au salarié ou à l'employeur."
        }
        if (allowance == null || !allowance.complete || ijss == null) {
            warnings += "Montant IJSS non confirmé : aucun flux monétaire n'est ajouté automatiquement."
        }

        val maintenanceKnown = when (absence.salaryTreatment) {
            AbsenceSalaryTreatmentV2.FULLY_MAINTAINED,
            AbsenceSalaryTreatmentV2.UNPAID -> true
            AbsenceSalaryTreatmentV2.PARTIALLY_MAINTAINED,
            AbsenceSalaryTreatmentV2.TO_CONFIRM -> false
        }
        if (!maintenanceKnown) {
            warnings += when (absence.salaryTreatment) {
                AbsenceSalaryTreatmentV2.PARTIALLY_MAINTAINED -> "Maintien employeur partiel : montant/règle exacts à confirmer avant calcul du net."
                AbsenceSalaryTreatmentV2.TO_CONFIRM -> "Maintien employeur à confirmer avant calcul du net."
                else -> ""
            }
        }

        val directEmployee = if (recipient == IjssRecipient.EMPLOYEE) ijss else null
        val employerReimbursement = if (recipient == IjssRecipient.EMPLOYER) ijss else null
        val safe = recipient != IjssRecipient.TO_CONFIRM && allowance?.complete == true &&
            !(absence.subrogation == AbsenceSubrogationV2.YES && absence.salaryTreatment == AbsenceSalaryTreatmentV2.UNPAID)

        return Result(
            ijssRecipient = recipient,
            directEmployeeIjssGross = directEmployee,
            employerIjssReimbursementGross = employerReimbursement,
            salaryMaintenanceKnown = maintenanceKnown,
            doubleCountSafe = safe,
            warnings = warnings.filter { it.isNotBlank() }.distinct()
        )
    }
}