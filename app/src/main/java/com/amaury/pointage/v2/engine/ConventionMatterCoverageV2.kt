package com.amaury.pointage.v2.engine

import java.time.LocalDate

/**
 * État de couverture d'une matière conventionnelle pour un IDCC, une période et,
 * lorsque nécessaire, une classification salariée / un statut professionnel.
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
        /** Vide = couverture de toute la convention ; sinon couverture de ce sous-champ. */
        val classification: ConventionClassificationV2 = ConventionClassificationV2(),
        /** Null = tous statuts ; sinon statut exact (ex. CADRE / NON_CADRE). */
        val professionalStatus: String? = null,
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

        fun statusMatches(value: String?): Boolean {
            val wanted = professionalStatus?.trim()?.uppercase()
            return wanted == null || wanted == value?.trim()?.uppercase()
        }
    }

    data class Snapshot(
        val state: State,
        val record: Record?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun resolve(
        records: List<Record>,
        idcc: String,
        matter: Matter,
        date: LocalDate,
        classification: ConventionClassificationV2 = ConventionClassificationV2(),
        professionalStatus: String? = null
    ): Snapshot {
        val normalized = ConventionMinimumSalaryV2.normalizeIdcc(idcc)
        val matching = records.filter {
            it.structurallyValid() &&
                ConventionMinimumSalaryV2.normalizeIdcc(it.idcc) == normalized &&
                it.matter == matter &&
                it.activeOn(date) &&
                classification.matches(it.classification) &&
                it.statusMatches(professionalStatus)
        }
        if (matching.isEmpty()) {
            return incomplete(normalized, matter, classification, professionalStatus, "analyse KALI non confirmée pour cette période")
        }

        val latestDate = matching.maxOf { it.effectiveFrom }
        val latest = matching.filter { it.effectiveFrom == latestDate }
        fun specificity(record: Record) = record.classification.specificity() + if (record.professionalStatus == null) 0 else 1
        val maxSpecificity = latest.maxOf(::specificity)
        val best = latest.filter { specificity(it) == maxSpecificity }
        val states = best.map { it.state }.distinct()
        if (states.size > 1) {
            return incomplete(normalized, matter, classification, professionalStatus, "états de couverture contradictoires sur la même période")
        }

        val selected = best.maxByOrNull { it.checkedAtMs }!!
        return Snapshot(
            state = selected.state,
            record = selected,
            reliable = selected.state != State.INCOMPLETE,
            warnings = if (selected.state == State.INCOMPLETE) {
                listOf("Convention IDCC $normalized — ${matterLabel(matter)}${scopeSuffix(classification, professionalStatus)} : analyse officielle incomplète ; aucune absence de droit n'est déduite.")
            } else emptyList()
        )
    }

    private fun incomplete(
        idcc: String,
        matter: Matter,
        classification: ConventionClassificationV2,
        professionalStatus: String?,
        reason: String
    ) = Snapshot(
        state = State.INCOMPLETE,
        record = null,
        reliable = false,
        warnings = listOf("Convention IDCC $idcc — ${matterLabel(matter)}${scopeSuffix(classification, professionalStatus)} : $reason ; aucun droit n'est supposé absent.")
    )

    private fun scopeSuffix(classification: ConventionClassificationV2, professionalStatus: String?): String {
        val parts = buildList {
            if (!classification.isEmpty()) add(classification.label())
            professionalStatus?.trim()?.takeIf { it.isNotBlank() }?.let { add("statut ${it.uppercase()}") }
        }
        return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
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
