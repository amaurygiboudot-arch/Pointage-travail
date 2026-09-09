package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import java.time.YearMonth
import kotlin.math.abs

/**
 * Agrège les faits RGDU mensuels déjà fiabilisés vers l'entrée annuelle 2026.
 *
 * Ce composant ne déduit jamais une année complète depuis les seuls paramètres courants :
 * les douze mois doivent être présents explicitement, cohérents entre eux et couverts par
 * un contexte annuel confirmé. Les avances fournies ici sont uniquement les avances RGDU
 * automatiques correspondant aux mêmes faits mensuels ; un total patronal global ne doit
 * jamais être utilisé comme substitut.
 */
object EmployerGeneralReductionAnnualInputV2 {
    data class Month(
        val period: YearMonth,
        val reductionRemunerationMonthly: Double?,
        val additionalPaidMinutes: Double?,
        val automaticRgduAdvanceAmount: Double?,
        val workforceBand: EmployerWorkforceContributionsV2.Band?,
        val contractType: ContractTypeV2?,
        val contractualWeeklyMinutes: Int?,
        val fullMonthPresent: Boolean?,
        val standardCommonLawCaseConfirmed: Boolean?,
        val paidHoursComplete: Boolean?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String> = emptyList()
    )

    data class Result(
        val annualInput: EmployerGeneralReductionAnnual2026V2.Input?,
        val monthlyAdvances: List<EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance>,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        year: Int,
        months: List<Month>,
        annualContext: EmployerGeneralReductionAnnualContextV2.Snapshot
    ): Result {
        if (year != 2026) {
            return blocked("RGDU annuelle : agrégation non intégrée pour $year.")
        }

        val contextBlockers = buildList {
            if (!annualContext.reliable) addAll(annualContext.warnings)
            if (annualContext.source.isNullOrBlank()) {
                add("RGDU annuelle : source du contexte annuel absente ; agrégation bloquée.")
            }
            if (annualContext.fullCalendarYearPresent != true) {
                add("RGDU annuelle : année civile complète non confirmée.")
            }
            if (annualContext.standardCommonLawCaseConfirmed != true) {
                add("RGDU annuelle : cas de droit commun non confirmé sur toute l'année.")
            }
            if (annualContext.homogeneousAnnualParametersConfirmed != true) {
                add("RGDU annuelle : stabilité annuelle du contrat, de la durée contractuelle et de l'effectif non confirmée.")
            }
            addAll(annualContext.warnings)
        }.distinct()
        if (contextBlockers.isNotEmpty()) return blocked(contextBlockers)

        val duplicatePeriods = months.groupingBy { it.period }.eachCount().filterValues { it > 1 }.keys
        if (duplicatePeriods.isNotEmpty()) {
            return blocked("RGDU annuelle : plusieurs entrées existent pour un même mois.")
        }

        val expected = (1..12).map { YearMonth.of(year, it) }.toSet()
        val actual = months.map { it.period }.toSet()
        if (actual != expected) {
            val missing = (expected - actual).sorted().joinToString(", ") { it.toString() }
            val foreign = (actual - expected).sorted().joinToString(", ") { it.toString() }
            return blocked(
                buildList {
                    if (missing.isNotBlank()) add("RGDU annuelle : mois manquants : $missing.")
                    if (foreign.isNotBlank()) add("RGDU annuelle : mois hors année attendue : $foreign.")
                    if (isEmpty()) add("RGDU annuelle : les douze mois de $year doivent être présents explicitement.")
                }
            )
        }

        val ordered = months.sortedBy { it.period }
        val monthlyBlockers = mutableListOf<String>()
        ordered.forEach { month ->
            val label = month.period.toString()
            if (!month.reliable) {
                monthlyBlockers += month.warnings.ifEmpty {
                    listOf("RGDU annuelle : faits mensuels non fiables pour $label.")
                }
            }
            if (month.warnings.isNotEmpty()) {
                monthlyBlockers += month.warnings
            }
            if (month.source.isNullOrBlank()) {
                monthlyBlockers += "RGDU annuelle : source mensuelle absente pour $label."
            }
            if (month.fullMonthPresent != true) {
                monthlyBlockers += "RGDU annuelle : mois complet non confirmé pour $label."
            }
            if (month.standardCommonLawCaseConfirmed != true) {
                monthlyBlockers += "RGDU annuelle : cas de droit commun non confirmé pour $label."
            }
            if (month.paidHoursComplete != true) {
                monthlyBlockers += "RGDU annuelle : exhaustivité des heures rémunérées non confirmée pour $label."
            }
            val remuneration = month.reductionRemunerationMonthly
            if (remuneration == null || !remuneration.isFinite() || remuneration < 0.0) {
                monthlyBlockers += "RGDU annuelle : rémunération de référence invalide ou inconnue pour $label."
            }
            val additional = month.additionalPaidMinutes
            if (additional == null || !additional.isFinite() || additional < 0.0) {
                monthlyBlockers += "RGDU annuelle : heures supplémentaires/complémentaires inconnues ou invalides pour $label."
            }
            val advance = month.automaticRgduAdvanceAmount
            if (advance == null || !advance.isFinite() || advance < 0.0) {
                monthlyBlockers += "RGDU annuelle : avance RGDU automatique inconnue ou invalide pour $label."
            }
            if (month.workforceBand == null) {
                monthlyBlockers += "RGDU annuelle : tranche d'effectif inconnue pour $label."
            }
            if (month.contractType != ContractTypeV2.FULL_TIME && month.contractType != ContractTypeV2.PART_TIME) {
                monthlyBlockers += "RGDU annuelle : type de contrat non couvert ou inconnu pour $label."
            }
            if (month.contractualWeeklyMinutes == null || month.contractualWeeklyMinutes <= 0) {
                monthlyBlockers += "RGDU annuelle : durée contractuelle hebdomadaire inconnue ou invalide pour $label."
            }
        }
        if (monthlyBlockers.isNotEmpty()) return blocked(monthlyBlockers.distinct())

        val bands = ordered.mapNotNull { it.workforceBand }.distinct()
        val contractTypes = ordered.mapNotNull { it.contractType }.distinct()
        val weeklyDurations = ordered.mapNotNull { it.contractualWeeklyMinutes }.distinct()
        val homogeneityBlockers = buildList {
            if (bands.size != 1) add("RGDU annuelle : la tranche d'effectif varie au cours de l'année ; cas standard bloqué.")
            if (contractTypes.size != 1) add("RGDU annuelle : le type de contrat varie au cours de l'année ; cas standard bloqué.")
            if (weeklyDurations.size != 1) add("RGDU annuelle : la durée contractuelle hebdomadaire varie au cours de l'année ; cas standard bloqué.")
        }
        if (homogeneityBlockers.isNotEmpty()) return blocked(homogeneityBlockers)

        val band = bands.single()
        val contractType = contractTypes.single()
        val contractualWeeklyMinutes = weeklyDurations.single()

        // Une avance automatique transmise doit correspondre exactement aux mêmes faits mensuels.
        // Cela évite de régulariser une ancienne avance contre un contexte recalculé ou modifié.
        ordered.forEach { month ->
            val recalculated = EmployerGeneralReduction2026V2.calculateMonthlyAdvance(
                EmployerGeneralReduction2026V2.Input(
                    year = year,
                    reductionRemunerationMonthly = month.reductionRemunerationMonthly!!,
                    workforceBand = month.workforceBand,
                    contractType = month.contractType,
                    contractualWeeklyMinutes = month.contractualWeeklyMinutes,
                    additionalPaidMinutes = month.additionalPaidMinutes,
                    fullMonthPresent = month.fullMonthPresent,
                    standardCommonLawCaseConfirmed = month.standardCommonLawCaseConfirmed
                )
            )
            val expectedAdvance = recalculated.amount?.takeIf { recalculated.reliable }
                ?: return blocked(
                    recalculated.warnings.ifEmpty {
                        listOf("RGDU annuelle : avance mensuelle impossible à vérifier pour ${month.period}.")
                    }
                )
            if (abs(expectedAdvance - month.automaticRgduAdvanceAmount!!) > 0.005) {
                return blocked(
                    "RGDU annuelle : l'avance automatique enregistrée pour ${month.period} ne correspond plus aux faits mensuels ; régularisation bloquée."
                )
            }
        }

        val annualRemuneration = ordered.sumOf { it.reductionRemunerationMonthly!! }
        val annualAdditionalMinutes = ordered.sumOf { it.additionalPaidMinutes!! }
        if (!annualRemuneration.isFinite() || annualRemuneration < 0.0) {
            return blocked("RGDU annuelle : cumul de rémunération annuel invalide.")
        }
        if (!annualAdditionalMinutes.isFinite() || annualAdditionalMinutes < 0.0) {
            return blocked("RGDU annuelle : cumul annuel d'heures supplémentaires/complémentaires invalide.")
        }

        val annualInput = EmployerGeneralReductionAnnual2026V2.Input(
            year = year,
            annualReductionRemuneration = annualRemuneration,
            workforceBand = band,
            contractType = contractType,
            contractualWeeklyMinutes = contractualWeeklyMinutes,
            additionalPaidMinutesAnnual = annualAdditionalMinutes,
            fullCalendarYearPresent = annualContext.fullCalendarYearPresent,
            standardCommonLawCaseConfirmed = annualContext.standardCommonLawCaseConfirmed,
            homogeneousAnnualParametersConfirmed = annualContext.homogeneousAnnualParametersConfirmed
        )
        val advances = ordered.map {
            EmployerGeneralReductionAnnualRegularizationV2.MonthlyAdvance(
                month = it.period.monthValue,
                amount = it.automaticRgduAdvanceAmount!!
            )
        }

        return Result(
            annualInput = annualInput,
            monthlyAdvances = advances,
            reliable = true,
            warnings = emptyList()
        )
    }

    private fun blocked(message: String) = blocked(listOf(message))

    private fun blocked(warnings: List<String>) = Result(
        annualInput = null,
        monthlyAdvances = emptyList(),
        reliable = false,
        warnings = warnings.ifEmpty {
            listOf("RGDU annuelle : agrégation des faits mensuels bloquée.")
        }.distinct()
    )
}
