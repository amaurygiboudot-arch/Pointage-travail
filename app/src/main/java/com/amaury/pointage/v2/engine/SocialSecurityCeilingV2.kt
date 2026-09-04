package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.ContractTypeV2
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Source unique du plafond de Sécurité sociale utilisé par les calculs de paie V2.
 *
 * Références 2026 : PMSS 4 005 €, article R.242-2 CSS et règles Agirc-Arrco.
 * Les réductions ne sont appliquées que lorsque HoraTrack dispose des données nécessaires.
 */
object SocialSecurityCeilingV2 {
    private const val PMSS_2026 = 4005.0
    private const val LEGAL_WEEKLY_MINUTES = 35 * 60
    private const val FULL_TIME_ANNUAL_DAYS_REFERENCE = 218.0

    /** Valeur mensuelle non proratisée du plafond pour une année connue. */
    fun fullMonthly(year: Int): Double? = when (year) {
        2026 -> PMSS_2026
        else -> null
    }

    data class Input(
        val year: Int,
        /** N'importe quel jour du mois de paie concerné. */
        val referenceDate: LocalDate,
        val contractType: ContractTypeV2?,
        val contractualWeeklyMinutes: Int?,
        /** Heures complémentaires réellement retenues dans l'estimation du mois. */
        val complementaryMinutes: Int? = null,
        val entryDate: LocalDate? = null,
        /** Null signifie contrat considéré en cours à la fin du mois. */
        val exitDate: LocalDate? = null,
        /** À alimenter lorsque le moteur Absences V2 sera branché. */
        val unpaidAbsenceDays: Int = 0,
        val forfaitAnnualDays: Double? = null
    )

    data class Snapshot(
        val fullMonthly: Double,
        val applicableMonthly: Double,
        val fourTimesApplicable: Double,
        val eightTimesApplicable: Double,
        val presenceRatio: Double,
        val workTimeRatio: Double,
        val complete: Boolean,
        val warnings: List<String>
    )

    fun calculate(input: Input): Snapshot {
        val fullMonthly = fullMonthly(input.year)
        if (fullMonthly == null) {
            return Snapshot(
                fullMonthly = 0.0,
                applicableMonthly = 0.0,
                fourTimesApplicable = 0.0,
                eightTimesApplicable = 0.0,
                presenceRatio = 0.0,
                workTimeRatio = 0.0,
                complete = false,
                warnings = listOf("Plafond de Sécurité sociale : barème non intégré pour ${input.year}.")
            )
        }

        val warnings = mutableListOf<String>()
        var complete = true
        val monthStart = input.referenceDate.withDayOfMonth(1)
        val monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth())

        val employmentStart = maxOf(monthStart, input.entryDate ?: monthStart)
        val employmentEnd = minOf(monthEnd, input.exitDate ?: monthEnd)
        val employedDays = if (employmentEnd < employmentStart) {
            0
        } else {
            ChronoUnit.DAYS.between(employmentStart, employmentEnd.plusDays(1)).toInt()
        }
        val absenceDays = input.unpaidAbsenceDays.coerceIn(0, employedDays)
        val ceilingDays = (employedDays - absenceDays).coerceAtLeast(0)
        val presenceRatio = ceilingDays.toDouble() / monthStart.lengthOfMonth().toDouble()

        if (input.entryDate == null) {
            complete = false
            warnings += "Plafond SS : date d'entrée absente, aucune réduction pour une éventuelle entrée en cours de mois n'est inventée."
        }
        if (absenceDays > 0) {
            warnings += "Plafond SS réduit de $absenceDays jour(s) d'absence non rémunérée."
        }

        val workTimeRatio = when (input.contractType) {
            ContractTypeV2.PART_TIME -> {
                val weekly = input.contractualWeeklyMinutes
                if (weekly == null || weekly <= 0) {
                    complete = false
                    warnings += "Plafond SS temps partiel : durée contractuelle absente, réduction non calculable."
                    1.0
                } else if (presenceRatio <= 0.0) {
                    0.0
                } else {
                    val complementary = input.complementaryMinutes
                    if (complementary == null) {
                        complete = false
                        warnings += "Plafond SS temps partiel : heures complémentaires du mois inconnues, calcul conservateur sans heures complémentaires."
                    }
                    val contractualMonthly = weekly * 52.0 / 12.0
                    val legalMonthly = LEGAL_WEEKLY_MINUTES * 52.0 / 12.0
                    val contractualDuringPresence = contractualMonthly * presenceRatio
                    val legalDuringPresence = legalMonthly * presenceRatio
                    ((contractualDuringPresence + (complementary ?: 0).coerceAtLeast(0)) / legalDuringPresence)
                        .coerceIn(0.0, 1.0)
                }
            }

            ContractTypeV2.FORFAIT_DAYS -> {
                val days = input.forfaitAnnualDays
                if (days == null || days <= 0.0) {
                    complete = false
                    warnings += "Plafond SS forfait jours : nombre annuel de jours absent, réduction éventuelle non calculée."
                    1.0
                } else {
                    (days / FULL_TIME_ANNUAL_DAYS_REFERENCE).coerceIn(0.0, 1.0)
                }
            }

            ContractTypeV2.FORFAIT_HOURS -> {
                // Un forfait heures n'est pas automatiquement assimilé à un temps partiel.
                // Sans qualification explicite, HoraTrack refuse d'inventer un prorata.
                1.0
            }

            else -> 1.0
        }

        val applicable = (fullMonthly * presenceRatio * workTimeRatio).coerceIn(0.0, fullMonthly)
        if (applicable + 0.01 < fullMonthly) {
            warnings += "Plafond SS ${input.year} appliqué : ${String.format(java.util.Locale.FRANCE, "%.2f", applicable)} € au lieu de ${String.format(java.util.Locale.FRANCE, "%.2f", fullMonthly)} €."
        }

        return Snapshot(
            fullMonthly = fullMonthly,
            applicableMonthly = applicable,
            fourTimesApplicable = applicable * 4.0,
            eightTimesApplicable = applicable * 8.0,
            presenceRatio = presenceRatio,
            workTimeRatio = workTimeRatio,
            complete = complete,
            warnings = warnings.distinct()
        )
    }
}
