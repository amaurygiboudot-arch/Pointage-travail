package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * État de couverture d'une matière conventionnelle pour un IDCC et une période.
 *
 * CONFIRMED_NO_RULE n'est valable qu'après une recherche officielle exhaustive :
 * l'absence d'une règle enregistrée ne suffit jamais à conclure à l'absence de droit.
 */
object ConventionMatterCoverageV2 {
    enum class Matter {
        MINIMUM_SALARY,
        SENIORITY_PREMIUM,
        SICKNESS_MAINTENANCE,
        PROVIDENT,
        OVERTIME,
        NIGHT,
        SATURDAY,
        SUNDAY,
        PUBLIC_HOLIDAY,
        MEAL_BASKET,
        OTHER_PREMIUM
    }

    enum class State {
        CONFIRMED_RULES,
        CONFIRMED_NO_RULE,
        INCOMPLETE
    }

    data class Record(
        val idcc: String,
        val matter: Matter,
        val effectiveFrom: LocalDate,
        val effectiveTo: LocalDate? = null,
        val state: State,
        val source: String,
        val checkedAtMs: Long
    ) {
        fun structurallyValid(): Boolean = ConventionMinimumSalaryV2.normalizeIdcc(idcc).isNotBlank() &&
            source.isNotBlank() &&
            checkedAtMs > 0L &&
            (effectiveTo == null || !effectiveTo.isBefore(effectiveFrom))

        fun activeOn(date: LocalDate): Boolean = !date.isBefore(effectiveFrom) &&
            (effectiveTo == null || !date.isAfter(effectiveTo))
    }

    data class Snapshot(
        val state: State,
        val record: Record?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(records: List<Record>, idcc: String, matter: Matter, date: LocalDate): Snapshot {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val matching = records.filter {
            it.structurallyValid() &&
                ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized &&
                it.matter == matter &&
                it.activeOn(date)
        }
        if (matching.isEmpty()) {
            return Snapshot(
                state = State.INCOMPLETE,
                record = null,
                reliable = false,
                warnings = listOf("Convention IDCC $normalized — ${matterLabel(matter)} : analyse KALI non confirmée pour cette période.")
            )
        }

        val latestDate = matching.maxOf { it.effectiveFrom }
        val latest = matching.filter { it.effectiveFrom == latestDate }
        val states = latest.map { it.state }.distinct()
        if (states.size > 1) {
            return Snapshot(
                state = State.INCOMPLETE,
                record = null,
                reliable = false,
                warnings = listOf("Convention IDCC $normalized — ${matterLabel(matter)} : états de couverture contradictoires sur la même période ; contrôle requis.")
            )
        }

        val selected = latest.maxByOrNull { it.checkedAtMs }!!
        return Snapshot(
            state = selected.state,
            record = selected,
            reliable = selected.state != State.INCOMPLETE,
            warnings = if (selected.state == State.INCOMPLETE) {
                listOf("Convention IDCC $normalized — ${matterLabel(matter)} : analyse officielle incomplète ; aucune absence de droit n'est déduite.")
            } else emptyList()
        )
    }

    private fun matterLabel(matter: Matter): String = when (matter) {
        Matter.MINIMUM_SALARY -> "minimum salarial"
        Matter.SENIORITY_PREMIUM -> "prime d'ancienneté"
        Matter.SICKNESS_MAINTENANCE -> "maintien de salaire maladie"
        Matter.PROVIDENT -> "prévoyance"
        Matter.OVERTIME -> "heures supplémentaires"
        Matter.NIGHT -> "travail de nuit"
        Matter.SATURDAY -> "travail du samedi"
        Matter.SUNDAY -> "travail du dimanche"
        Matter.PUBLIC_HOLIDAY -> "jours fériés"
        Matter.MEAL_BASKET -> "panier / indemnité repas"
        Matter.OTHER_PREMIUM -> "autres primes conventionnelles"
    }
}
