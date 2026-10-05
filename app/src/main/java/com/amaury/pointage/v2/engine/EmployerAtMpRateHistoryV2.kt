package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.time.YearMonth

/** Taux AT/MP notifiés pour un établissement, sans taux ni période supposés. */
object EmployerAtMpRateHistoryV2 {
    data class Record(
        val id: String,
        val establishmentSiret: String,
        /** Taux décimal : 2,08 % = 0,0208. */
        val rate: Double,
        val effectiveFrom: LocalDate,
        /** Dernier jour inclus, absent si la notification n'a pas de fin connue. */
        val effectiveTo: LocalDate? = null,
        val source: String,
        val confirmedAtMs: Long
    )

    data class Snapshot(
        val rate: Double?,
        val establishmentSiret: String?,
        val source: String?,
        val reliable: Boolean,
        val warnings: List<String>
    )

    fun structurallyValid(record: Record): Boolean = record.id.isNotBlank() &&
        validSiret(record.establishmentSiret) && record.rate.isFinite() && record.rate in 0.0..1.0 &&
        record.source.isNotBlank() && record.confirmedAtMs > 0L &&
        (record.effectiveTo == null || !record.effectiveTo.isBefore(record.effectiveFrom))

    fun validSiret(siret: String): Boolean = siret.length == 14 && siret.all { it in '0'..'9' }

    /** Le calcul mensuel existant n'accepte qu'un taux constant confirmé sur tous les jours. */
    fun resolve(records: List<Record>, establishmentSiret: String, period: YearMonth): Snapshot {
        if (!validSiret(establishmentSiret)) return unavailable(
            "AT/MP employeur : SIRET de l'établissement à confirmer pour le taux daté."
        )
        if (records.any { !structurallyValid(it) } || records.groupingBy { it.id }.eachCount().any { it.value > 1 }) {
            return unavailable("AT/MP employeur : une version enregistrée est incohérente ou non confirmée ; aucun taux historique n'est appliqué.")
        }
        val start = period.atDay(1)
        val end = period.atEndOfMonth()
        val candidates = records.filter {
            it.establishmentSiret == establishmentSiret && !it.effectiveFrom.isAfter(end) &&
                (it.effectiveTo == null || !it.effectiveTo.isBefore(start))
        }
        if (candidates.isEmpty()) return unavailable(
            "AT/MP employeur : taux daté, source et établissement à confirmer pour $period ; coût employeur incomplet."
        )
        val used = linkedSetOf<Record>()
        for (dayNumber in 1..period.lengthOfMonth()) {
            val day = period.atDay(dayNumber)
            val active = candidates.filter { !it.effectiveFrom.isAfter(day) && (it.effectiveTo == null || !it.effectiveTo.isBefore(day)) }
            if (active.size > 1) return unavailable(
                "AT/MP employeur : plusieurs versions se chevauchent pour l'établissement sur $period ; coût employeur incomplet."
            )
            if (active.isEmpty()) return unavailable(
                "AT/MP employeur : l'historique confirmé ne couvre pas tout $period ; aucun taux courant ne remplace les jours manquants."
            )
            used += active.single()
        }
        val rate = used.first().rate
        if (used.any { it.rate != rate }) return unavailable(
            "AT/MP employeur : le taux varie pendant $period ; calcul mensuel à répartir avant de confirmer le coût employeur."
        )
        return Snapshot(rate, establishmentSiret, used.map { it.source }.distinct().joinToString(" | "), true, emptyList())
    }

    fun unavailable(warning: String) = Snapshot(null, null, null, false, listOf(warning))
}
