package com.amaury.pointage.v2

import android.content.Context
import com.amaury.pointage.ConventionCatalog
import com.amaury.pointage.RgduPayrollInputBridgeV2
import com.amaury.pointage.SalaryCompanyStore
import com.amaury.pointage.V2SalaryAdapter
import com.amaury.pointage.v2.engine.CompanyBenefitInKindResolverV2
import com.amaury.pointage.v2.engine.EmployerGeneralReduction2026V2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionAnnual2026V2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionAnnualContextV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionAnnualInputV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionAnnualRegularizationV2
import com.amaury.pointage.v2.engine.EmployerGeneralReductionContextV2
import com.amaury.pointage.v2.engine.EmployerWorkforceContributionsV2
import java.time.YearMonth

/**
 * Pont Android entre les douze calculs mensuels Salaire V2 et le noyau RGDU annuel.
 *
 * Les paramètres de contrat utilisés ici viennent exclusivement du snapshot annuel historique
 * confirmé. Les préférences de contrat courantes ne sont jamais recopiées dans le passé.
 *
 * Les avances retournées par ce pont sont des avances automatiques HoraTrack reconstruites à
 * partir des faits mensuels. Elles ne constituent pas, à elles seules, la preuve du montant
 * effectivement déclaré/appliqué sur une DSN ou un bulletin historique.
 */
object CompanyEmployerGeneralReductionAnnualPayrollBridgeV2 {
    enum class AdvanceBasis {
        RECONSTRUCTED_AUTOMATIC
    }

    data class Result(
        val annualEntitlement: EmployerGeneralReductionAnnual2026V2.Result?,
        val regularization: EmployerGeneralReductionAnnualRegularizationV2.Result?,
        val monthlyFacts: List<EmployerGeneralReductionAnnualInputV2.Month>,
        val advanceBasis: AdvanceBasis?,
        val reliable: Boolean,
        val warnings: List<String>,
        val notes: List<String>
    )

    fun resolve(
        context: Context,
        companyId: String,
        year: Int
    ): Result {
        if (companyId.isBlank()) {
            return blocked("RGDU annuelle : entreprise non identifiée.")
        }
        if (year != 2026) {
            return blocked("RGDU annuelle : passerelle Salaire V2 non intégrée pour $year.")
        }

        val annualContext = CompanyEmployerGeneralReductionAnnualContextStoreV2.resolve(
            context = context,
            companyId = companyId,
            year = year
        )
        val annualContextBlockers = annualContextBlockers(annualContext)
        if (annualContextBlockers.isNotEmpty()) {
            return blocked(annualContextBlockers)
        }

        val company = SalaryCompanyStore.list(context).firstOrNull { it.id == companyId }
            ?: return blocked("RGDU annuelle : entreprise V2 introuvable.")
        val prefs = SalaryCompanyStore.prefs(context, companyId)
        val idcc = company.idcc.ifBlank { prefs.getString("company_idcc", "").orEmpty() }
        val convention = idcc.takeIf { it.isNotBlank() }
            ?.let { ConventionCatalog.findByIdcc(context, it) }
            ?.takeIf { it.idcc.isNotBlank() }
            ?: return blocked("RGDU annuelle : convention collective à confirmer avant la reconstruction des 12 mois.")

        val monthlyFacts = mutableListOf<EmployerGeneralReductionAnnualInputV2.Month>()
        for (monthNumber in 1..12) {
            val period = YearMonth.of(year, monthNumber)
            val salary = runCatching {
                V2SalaryAdapter.calculateForCompany(
                    context = context,
                    company = company,
                    year = year,
                    month = monthNumber - 1,
                    convention = convention
                )
            }.getOrElse {
                return blocked(
                    warning = "RGDU annuelle : résultat Salaire V2 indisponible pour $period ; reconstruction annuelle bloquée.",
                    monthlyFacts = monthlyFacts
                )
            }

            val benefits = CompanyBenefitInKindStoreV2.resolve(context, companyId, period)
            val workforce = CompanyWorkforceContributionStoreV2.resolve(context, companyId, period)
            val monthlyContext = CompanyEmployerGeneralReductionContextStoreV2.resolve(context, companyId, period)

            monthlyFacts += buildMonth(
                period = period,
                salary = salary,
                benefits = benefits,
                workforce = workforce,
                monthlyContext = monthlyContext,
                annualContext = annualContext
            )
        }

        val prepared = EmployerGeneralReductionAnnualInputV2.resolve(
            year = year,
            months = monthlyFacts,
            annualContext = annualContext
        )
        if (!prepared.reliable || prepared.annualInput == null) {
            return blocked(prepared.warnings, monthlyFacts)
        }

        val annual = EmployerGeneralReductionAnnual2026V2.calculate(prepared.annualInput)
        if (!annual.reliable || annual.amount == null) {
            return Result(
                annualEntitlement = annual,
                regularization = null,
                monthlyFacts = monthlyFacts,
                advanceBasis = AdvanceBasis.RECONSTRUCTED_AUTOMATIC,
                reliable = false,
                warnings = annual.warnings,
                notes = reconstructionNotes()
            )
        }

        val regularization = EmployerGeneralReductionAnnualRegularizationV2.resolve(
            year = year,
            annual = annual,
            advances = prepared.monthlyAdvances
        )
        return Result(
            annualEntitlement = annual,
            regularization = regularization,
            monthlyFacts = monthlyFacts,
            advanceBasis = AdvanceBasis.RECONSTRUCTED_AUTOMATIC,
            reliable = regularization.reliable,
            warnings = regularization.warnings,
            notes = reconstructionNotes()
        )
    }

    internal fun buildMonth(
        period: YearMonth,
        salary: V2SalaryAdapter.Result,
        benefits: CompanyBenefitInKindResolverV2.Snapshot,
        workforce: EmployerWorkforceContributionsV2.Snapshot,
        monthlyContext: EmployerGeneralReductionContextV2.Snapshot,
        annualContext: EmployerGeneralReductionAnnualContextV2.Snapshot
    ): EmployerGeneralReductionAnnualInputV2.Month {
        val contractType = annualContext.confirmedContractType
        val contractualWeeklyMinutes = annualContext.confirmedContractualWeeklyMinutes
        val payrollInput = RgduPayrollInputBridgeV2.resolve(
            salary = salary,
            contractType = contractType,
            benefitsInKindGross = benefits.totalGross.takeIf { benefits.reliable } ?: Double.NaN,
            paidHoursComplete = monthlyContext.paidHoursComplete
        )

        val monthlyAdvance = if (
            payrollInput.reliable &&
            payrollInput.reductionRemunerationMonthly != null &&
            payrollInput.additionalPaidMinutes != null
        ) {
            EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
                EmployerGeneralReduction2026V2.Input(
                    year = period.year,
                    reductionRemunerationMonthly = payrollInput.reductionRemunerationMonthly,
                    workforceBand = workforce.band,
                    contractType = contractType,
                    contractualWeeklyMinutes = contractualWeeklyMinutes,
                    additionalPaidMinutes = payrollInput.additionalPaidMinutes,
                    fullMonthPresent = monthlyContext.fullMonthPresent,
                    standardCommonLawCaseConfirmed = monthlyContext.standardCommonLawCaseConfirmed
                )
            )
        } else null

        val source = listOfNotNull(
            monthlyContext.source?.trim()?.takeIf { it.isNotBlank() },
            workforce.source?.trim()?.takeIf { it.isNotBlank() }
        ).distinct().joinToString(" | ").takeIf { it.isNotBlank() }

        val warnings = buildList {
            addAll(monthlyContext.warnings)
            addAll(workforce.warnings)
            if (!benefits.reliable) addAll(benefits.warnings)
            if (!payrollInput.reliable) {
                addAll(payrollInput.warnings)
                addAll(salary.warnings)
            }
            if (annualContext.confirmedContractType == null) {
                add("RGDU annuelle : type de contrat historique exact absent pour ${period.year}.")
            }
            if (annualContext.confirmedContractualWeeklyMinutes == null) {
                add("RGDU annuelle : durée contractuelle historique exacte absente pour ${period.year}.")
            }
            if (source == null) {
                add("RGDU annuelle : provenance mensuelle insuffisante pour $period.")
            }
            monthlyAdvance?.warnings?.let(::addAll)
        }.distinct()

        val reliable =
            annualContext.confirmedContractType != null &&
                annualContext.confirmedContractualWeeklyMinutes != null &&
                monthlyContext.reliable &&
                workforce.reliable &&
                benefits.reliable &&
                payrollInput.reliable &&
                monthlyAdvance?.reliable == true &&
                monthlyAdvance.amount != null &&
                source != null &&
                warnings.isEmpty()

        return EmployerGeneralReductionAnnualInputV2.Month(
            period = period,
            reductionRemunerationMonthly = payrollInput.reductionRemunerationMonthly,
            additionalPaidMinutes = payrollInput.additionalPaidMinutes,
            automaticRgduAdvanceAmount = monthlyAdvance?.amount,
            workforceBand = workforce.band,
            contractType = contractType,
            contractualWeeklyMinutes = contractualWeeklyMinutes,
            fullMonthPresent = monthlyContext.fullMonthPresent,
            standardCommonLawCaseConfirmed = monthlyContext.standardCommonLawCaseConfirmed,
            paidHoursComplete = monthlyContext.paidHoursComplete,
            source = source,
            reliable = reliable,
            warnings = warnings
        )
    }

    private fun annualContextBlockers(
        context: EmployerGeneralReductionAnnualContextV2.Snapshot
    ): List<String> = buildList {
        if (!context.reliable) addAll(context.warnings)
        if (context.source.isNullOrBlank()) add("RGDU annuelle : source annuelle à confirmer.")
        if (context.fullCalendarYearPresent != true) add("RGDU annuelle : année civile complète non confirmée.")
        if (context.standardCommonLawCaseConfirmed != true) add("RGDU annuelle : cas de droit commun non confirmé sur toute l'année.")
        if (context.homogeneousAnnualParametersConfirmed != true) add("RGDU annuelle : stabilité annuelle des paramètres non confirmée.")
        if (context.confirmedWorkforceBand == null) add("RGDU annuelle : tranche d'effectif historique exacte à confirmer.")
        if (context.confirmedContractType == null) add("RGDU annuelle : type de contrat historique exact à confirmer.")
        if (context.confirmedContractualWeeklyMinutes == null) add("RGDU annuelle : durée contractuelle historique exacte à confirmer.")
        addAll(context.warnings)
    }.distinct()

    private fun reconstructionNotes() = listOf(
        "RGDU annuelle : les avances mensuelles sont reconstruites automatiquement depuis les faits Salaire V2 ; elles ne valent pas confirmation d'un montant effectivement déclaré en DSN."
    )

    private fun blocked(
        warning: String,
        monthlyFacts: List<EmployerGeneralReductionAnnualInputV2.Month> = emptyList()
    ) = blocked(listOf(warning), monthlyFacts)

    private fun blocked(
        warnings: List<String>,
        monthlyFacts: List<EmployerGeneralReductionAnnualInputV2.Month> = emptyList()
    ) = Result(
        annualEntitlement = null,
        regularization = null,
        monthlyFacts = monthlyFacts,
        advanceBasis = null,
        reliable = false,
        warnings = warnings.ifEmpty { listOf("RGDU annuelle : calcul annuel bloqué.") }.distinct(),
        notes = emptyList()
    )
}
