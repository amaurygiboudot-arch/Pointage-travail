package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.v2.engine.PayrollCalculationTimelineResultV2
import com.amaury.pointage.v2.engine.PayrollCalculationTimelineV2

/**
 * Point d'entrée Android unique pour obtenir la timeline datée utilisable par Salaire V2.
 *
 * Le bridge agrège les historiques confirmés sans modifier les règles métier. L'IDCC demandé doit
 * appartenir à l'entreprise confirmée : un IDCC fourni pour une autre entreprise bloque le calcul
 * au lieu de croiser silencieusement un contrat et une convention sans lien.
 */
object V2PayrollCalculationTimelineBridge {
    const val COMPANY_STORE_WARNING =
        "Calcul Salaire V2 : stockage des entreprises non fiable ; la convention de l'entreprise ne peut pas être confirmée."
    const val COMPANY_WARNING =
        "Calcul Salaire V2 : entreprise absente du stockage confirmé ; la convention applicable ne peut pas être confirmée."
    const val COMPANY_IDCC_MISSING_WARNING =
        "Calcul Salaire V2 : IDCC de l'entreprise non confirmé ; aucune règle conventionnelle n'est appliquée automatiquement."
    const val COMPANY_IDCC_MISMATCH_WARNING =
        "Calcul Salaire V2 : l'IDCC demandé ne correspond pas à l'entreprise confirmée ; calcul segmenté bloqué."

    data class Snapshot(
        val contract: V2EmploymentContractPayrollBridge.Snapshot,
        val convention: V2ConventionRulePayrollBridge.Snapshot,
        val timeline: PayrollCalculationTimelineResultV2,
        val warnings: List<String>
    )

    fun resolve(
        context: Context,
        companyId: String,
        idcc: String,
        year: Int,
        monthZeroBased: Int
    ): Snapshot = resolveStored(
        companyStored = SalaryCompanyStore.readConfirmed(context),
        contractStored = V2EmploymentContractHistoryStore.readConfirmed(context),
        conventionStored = V2ConventionRuleStore.readConfirmed(context),
        companyId = companyId,
        idcc = idcc,
        year = year,
        monthZeroBased = monthZeroBased
    )

    internal fun resolveStored(
        companyStored: SalaryCompanyStore.ReadResult,
        contractStored: V2EmploymentContractHistoryStore.ReadResult,
        conventionStored: V2ConventionRuleStore.ReadResult,
        companyId: String,
        idcc: String,
        year: Int,
        monthZeroBased: Int
    ): Snapshot {
        val contract = V2EmploymentContractPayrollBridge.resolveStored(
            stored = contractStored,
            companyId = companyId,
            year = year,
            monthZeroBased = monthZeroBased
        )

        val company = SalaryCompanyStore.confirmedCompany(companyStored, companyId)
        val requestedIdcc = normalizeIdcc(idcc)
        val companyIdcc = company?.idcc?.let(::normalizeIdcc)
        val companyBlockers = buildList {
            when {
                !companyStored.reliable -> add(COMPANY_STORE_WARNING)
                company == null -> add(COMPANY_WARNING)
                companyIdcc == null -> add(COMPANY_IDCC_MISSING_WARNING)
                requestedIdcc == null || requestedIdcc != companyIdcc -> add(COMPANY_IDCC_MISMATCH_WARNING)
            }
        }
        val safeConventionStored = if (companyBlockers.isEmpty()) {
            conventionStored
        } else {
            conventionStored.copy(
                reliable = false,
                warnings = (conventionStored.warnings + companyStored.warnings + companyBlockers).distinct()
            )
        }

        val convention = V2ConventionRulePayrollBridge.resolveStored(
            stored = safeConventionStored,
            idcc = idcc,
            year = year,
            monthZeroBased = monthZeroBased
        )
        val timeline = PayrollCalculationTimelineV2.align(
            contracts = contract.resolution,
            rules = convention.resolution
        )
        return Snapshot(
            contract = contract,
            convention = convention,
            timeline = timeline,
            warnings = (companyStored.warnings + contract.warnings + convention.warnings + timeline.warnings).distinct()
        )
    }

    private fun normalizeIdcc(value: String): String? =
        value.trim().takeIf { it.isNotBlank() }?.padStart(4, '0')
}
