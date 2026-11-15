package com.amaury.pointage.v2

import org.json.JSONArray
import java.util.IdentityHashMap

/** Values in this report are schema constants and snapshot ordinals, never journal values. */
object LegacyJournalDiagnosticV2 {
    enum class Stage(val label: String) {
        SOURCE("Validation de la source"), V1("Lecture de l'ancien V1"), V2("Lecture de l'ancien V2"),
        ACCOUNT("Lecture du compte"), OLD_MERGE("Rapprochement des anciens journaux"),
        ACCOUNT_MERGE("Rapprochement avec le compte")
    }
    enum class Source(val label: String) {
        V1("Ancien journal V1"), V2("Ancien journal V2"), ACCOUNT("Journal du compte"),
        MERGE("Rapprochement des journaux")
    }
    enum class Code(val label: String) {
        UNREADABLE("Format illisible ou type incorrect"), UNSUPPORTED_FIELD("Champ non pris en charge"),
        REAL_ARRIVAL_MISSING("Arrivée réelle absente"), TIME_CONTRADICTION("Horodatages incompatibles"),
        PAUSE_UNQUALIFIED("Statut payé de la pause absent ou invalide"),
        OPEN_PAUSE_UNSUPPORTED("Pause ouverte non représentable dans ce format"),
        CURRENT_CONFLICT("États courants différents"), IDENTITY_CONFLICT("Même identifiant, contenus différents"),
        OVERLAP("Périodes qui se chevauchent"), HISTORY_INVALID("Historique invalide ; contrainte non détaillée"),
        CURRENT_INVALID("État courant invalide ; contrainte non détaillée"),
        ORPHAN_CURRENT("Métadonnées courantes sans arrivée réelle"),
        PAUSE_INVALID("Pause incompatible ; contrainte non détaillée"),
        INVALID_VALUE("Valeur incompatible avec le schéma"),
        UNVERIFIED("Contrôle interrompu ; cause non détaillée")
    }
    data class Reference(val source: Source, val row: Int? = null, val current: Boolean = false,
                         val pause: Int? = null, val segment: Int? = null) {
        fun render(): String = source.label + when {
            current -> " · état courant"
            row != null -> " · pointage n° $row"
            else -> ""
        } + (pause?.let { " · pause n° $it" } ?: "") + (segment?.let { " · segment n° $it" } ?: "")
    }
    data class Issue(val code: Code, val first: Reference, val second: Reference? = null,
                     val field: String? = null, val stage: Stage = Stage.SOURCE) {
        fun render(): String = listOfNotNull("Étape : " + stage.label, code.name + " — " + code.label, first.render(),
            second?.render(), field?.takeIf { it in fields }?.let { "Champ : $it" },
            "Premier obstacle observé. Les contrôles suivants ne sont pas vérifiés. Aucun transfert effectué.")
            .joinToString("\n")
    }
    // Even an exception or an unknown user-supplied JSON key cannot enter the rendered report.
    private val fields = setOf("data", "history", "arrivalTime", "entry", "exit", "exitTime",
        "countedEntryTime", "countedExitTime", "autoPauseMinutes", "pauses", "paid", "start", "end", "kind", "status", "source",
        "id", "session_id", "real_entry", "real_exit", "counted_entry", "counted_exit", "employer_id",
        "company_slot", "expected_end", "pause_start", "pause_source", "pause_paid", "work_segments",
        "closureOrigin", "departureObservedAtMs", "restored_legacy_provenance", "realEntry", "realExit",
        "countedEntry", "countedExit", "timeBasis", "workSegments", "companySlot", "employerId",
        "placeId", "placeLabel", "legacyFixedUnpaidPauseMs")

    internal data class Origins(val source: Source, val rows: List<Reference> = emptyList(),
                                val current: Reference = Reference(source, current = true))
    /** Sidecar provenance exists only during this analysis; it is never written into a journal. */
    internal class Trace {
        var stage = Stage.SOURCE
        var issue: Issue? = null
            private set
        private val origins = IdentityHashMap<Map<String, *>, Origins>()
        fun register(values: Map<String, *>, source: Source) {
            val count = runCatching { JSONArray(values["history"] as? String ?: "[]").length() }.getOrDefault(0)
            origins[values] = Origins(source, (1..count).map { Reference(source, it) })
        }
        fun register(values: Map<String, *>, value: Origins) { origins[values] = value }
        fun origin(values: Map<String, *>): Origins = origins[values] ?: Origins(Source.MERGE)
        fun alias(to: Map<String, *>, from: Map<String, *>) { origins[to] = origin(from) }
        fun <T> reading(source: Source, block: () -> T): T {
            val previous = stage
            stage = when (source) { Source.V1 -> Stage.V1; Source.V2 -> Stage.V2; Source.ACCOUNT -> Stage.ACCOUNT; Source.MERGE -> previous }
            return try { block() } finally { stage = previous }
        }
        fun ref(values: Map<String, *>, row: Int? = null): Reference = origin(values).let {
            if (row == null) it.current else it.rows.getOrNull(row - 1) ?: Reference(it.source, row)
        }
        fun fail(code: Code, first: Reference, field: String? = null, second: Reference? = null): Boolean {
            if (issue == null) issue = Issue(code, first, second, field?.takeIf { it in fields }, stage)
            return false
        }
    }
}
