package com.amaury.pointage.v2.engine

/**
 * Propriétaire unique de la valorisation monétaire des minutes majorées déjà qualifiées.
 *
 * Cette couche ne décide jamais quelles minutes sont nuit/samedi/dimanche/férié et ne choisit
 * aucun multiplicateur : elle consomme uniquement PayrollWeekV2 + PayrollRulesV2 déjà prouvés.
 */
object PayrollPremiumGrossV2 {
    fun calculate(
        week: PayrollWeekV2,
        grossHourlyRate: Double,
        rules: PayrollRulesV2
    ): Double {
        require(grossHourlyRate > 0.0 && grossHourlyRate.isFinite()) {
            "Taux horaire brut invalide"
        }
        validateWeek(week)

        var extras = 0.0
        rules.nightMultiplier?.let { multiplier ->
            validateMultiplier(multiplier, "nuit")
            if (week.nightMinutes > 0) {
                extras += week.nightMinutes / 60.0 * grossHourlyRate * (multiplier - 1.0)
            }
        }
        rules.saturdayMultiplier?.let { multiplier ->
            validateMultiplier(multiplier, "samedi")
            if (week.saturdayMinutes > 0) {
                extras += week.saturdayMinutes / 60.0 * grossHourlyRate * (multiplier - 1.0)
            }
        }
        rules.sundayMultiplier?.let { multiplier ->
            validateMultiplier(multiplier, "dimanche")
            if (week.sundayMinutes > 0) {
                extras += week.sundayMinutes / 60.0 * grossHourlyRate * (multiplier - 1.0)
            }
        }
        rules.publicHolidayMultiplier?.let { multiplier ->
            validateMultiplier(multiplier, "jour férié")
            if (week.publicHolidayMinutes > 0) {
                extras += week.publicHolidayMinutes / 60.0 * grossHourlyRate * (multiplier - 1.0)
            }
        }

        require(extras.isFinite() && extras >= 0.0) { "Montant de majoration invalide" }
        return extras
    }

    private fun validateWeek(week: PayrollWeekV2) {
        require(week.paidMinutes >= 0) { "Minutes payées invalides" }
        listOf(
            "nuit" to week.nightMinutes,
            "samedi" to week.saturdayMinutes,
            "dimanche" to week.sundayMinutes,
            "jour férié" to week.publicHolidayMinutes
        ).forEach { (label, minutes) ->
            require(minutes >= 0) { "Minutes $label invalides" }
            require(minutes <= week.paidMinutes) {
                "Minutes $label supérieures aux minutes payées de la semaine"
            }
        }
    }

    private fun validateMultiplier(multiplier: Double, label: String) {
        require(multiplier.isFinite() && multiplier >= 1.0) {
            "Multiplicateur $label invalide"
        }
    }
}
