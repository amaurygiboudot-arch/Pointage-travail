package com.amaury.pointage.v2.engine

import java.time.LocalDate
import java.time.YearMonth

/** Statut confirmé pour une période exacte ; aucune ancienne préférence ne fournit de statut. */
object CompanyProfessionalStatusResolverV2 {
    enum class Status { CADRE, NON_CADRE }
    data class Record(val id: String, val status: Status, val effectiveFrom: LocalDate, val effectiveTo: LocalDate? = null, val source: String, val confirmedAtMs: Long)
    data class Snapshot(val status: String?, val reliable: Boolean, val warnings: List<String>)
    fun valid(record: Record): Boolean = record.id.isNotBlank() && record.source.isNotBlank() && record.confirmedAtMs > 0 &&
        (record.effectiveTo == null || record.effectiveTo >= record.effectiveFrom)
    fun resolve(records: List<Record>, period: YearMonth): Snapshot {
        fun blocked(message: String) = Snapshot(null, false, listOf("Statut professionnel : $message"))
        if (records.any { !valid(it) } || records.map { it.id }.distinct().size != records.size)
            return blocked("historique invalide ; aucun statut courant n'est réutilisé.")
        val selected = mutableSetOf<Status>()
        for (dayNumber in 1..period.lengthOfMonth()) {
            val day = period.atDay(dayNumber)
            val covering = records.filter { it.effectiveFrom <= day && (it.effectiveTo == null || day <= it.effectiveTo) }
            if (covering.isEmpty()) return blocked("période $period non entièrement couverte ; à confirmer.")
            if (covering.size != 1) return blocked("versions en conflit pour $period ; calcul bloqué.")
            selected += covering.single().status
        }
        if (selected.size != 1) return blocked("changement de statut pendant $period ; calcul par segments requis.")
        return Snapshot(selected.single().name, true, emptyList())
    }
}
