package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.DeductionV2
import java.time.YearMonth

/**
 * Convertit uniquement les retenues salariales qui diminuent réellement le net versé,
 * confirmées, datées et sourcées, en entrées du moteur paie.
 *
 * La part salariale de prévoyance fiscalement non déductible n'est pas une seconde retenue cash :
 * elle fait déjà partie de la prévoyance salariale réellement prélevée et sert uniquement à la
 * réintégration du net imposable. La convertir ici en [DeductionV2] la déduirait deux fois.
 *
 * Les montants qui modifient seulement une assiette fiscale/sociale ne sont donc volontairement pas
 * transformés en retenues sur le salaire versé. L'absence d'un enregistrement daté n'est jamais assimilée
 * à zéro : pour confirmer l'absence d'une retenue cash sur un mois, un montant explicite de 0 doit exister.
 */
object CompanyEmployeeDeductionPayrollBridgeV2 {
    private val directEmployeeDeductionKinds = setOf(
        CompanyEmployeeDeductionResolverV2.Kind.MUTUAL_EMPLOYEE,
        CompanyEmployeeDeductionResolverV2.Kind.PROVIDENT_EMPLOYEE,
        CompanyEmployeeDeductionResolverV2.Kind.TRANSPORT_EMPLOYEE
    )

    data class Result(
        val deductions: List<DeductionV2>,
        /** Vrai seulement si chaque retenue salariale cash directe possède une valeur datée confirmée pour le mois. */
        val confirmedEmployeeDeductionsComplete: Boolean,
        val warnings: List<String>,
        val traces: List<String>
    )

    fun resolve(
        snapshot: CompanyEmployeeDeductionResolverV2.Snapshot,
        period: YearMonth
    ): Result {
        val deductions = mutableListOf<DeductionV2>()
        val warnings = mutableListOf<String>()
        val traces = mutableListOf<String>()
        var complete = true

        directEmployeeDeductionKinds
            .sortedBy { it.ordinal }
            .forEach { kind ->
                val value = snapshot[kind]
                when {
                    !value.reliable -> {
                        complete = false
                        warnings += value.warnings.ifEmpty {
                            listOf("${kind.label} : valeur non fiable pour $period ; aucune retenue n'est inventée.")
                        }
                    }
                    value.amount == null -> {
                        complete = false
                        warnings += "${kind.label} : aucun montant daté confirmé pour $period ; aucune retenue n'est inventée."
                    }
                    else -> {
                        val amount = value.amount
                        if (!amount.isFinite() || amount < 0.0) {
                            complete = false
                            warnings += "${kind.label} : montant invalide pour $period ; aucune retenue n'est inventée."
                        } else {
                            if (amount > 0.0) {
                                deductions += DeductionV2(
                                    id = "company_employee_${kind.name.lowercase()}",
                                    label = kind.label,
                                    amount = amount,
                                    recurring = true
                                )
                            }
                            traces += buildString {
                                append(kind.label)
                                append(" : ")
                                append(amount)
                                append(" € confirmés pour ")
                                append(period)
                                value.source?.takeIf { it.isNotBlank() }?.let { append(" — source ").append(it) }
                            }
                        }
                    }
                }
            }

        return Result(
            deductions = deductions,
            confirmedEmployeeDeductionsComplete = complete,
            warnings = warnings.distinct(),
            traces = traces.distinct()
        )
    }
}
