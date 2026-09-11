package com.amaury.pointage.v2.ui

import com.amaury.pointage.v2.engine.TimeEngineV2
import com.amaury.pointage.v2.engine.WorkTimePolicyV2
import com.amaury.pointage.v2.model.DecisionStatusV2
import com.amaury.pointage.v2.model.PauseV2
import com.amaury.pointage.v2.model.WorkSessionV2
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Rendu canonique de l'onglet Historique.
 *
 * L'ecran principal et ses filtres doivent afficher la meme chronologie et les memes
 * durees. Une duree que le moteur ne peut pas certifier reste explicitement a confirmer ;
 * elle ne devient jamais silencieusement 00h00.
 */
object HistoryTextFormatterV2 {
    data class Options(
        val showEntry: Boolean = true,
        val showPause: Boolean = true,
        val showExit: Boolean = true,
        val employerNames: Map<String, String> = emptyMap(),
        val emptyMessage: String = "Aucun historique."
    )

    fun selectSessions(
        sessions: List<WorkSessionV2>,
        nowMs: Long,
        todayOnly: Boolean = false,
        query: String = "",
        employerNames: Map<String, String> = emptyMap()
    ): List<WorkSessionV2> {
        val normalizedQuery = query.trim().lowercase(Locale.FRANCE)
        val today = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = nowMs }

        return sessions
            .asSequence()
            .filter { session ->
                if (!todayOnly) {
                    true
                } else {
                    val arrival = session.realArrivalMs ?: return@filter false
                    val date = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = arrival }
                    date.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                        date.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
                }
            }
            .filter { session ->
                if (normalizedQuery.isBlank()) {
                    true
                } else {
                    val arrival = session.realArrivalMs
                    val date = arrival?.let {
                        SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it))
                    }.orEmpty()
                    val employer = session.employerId
                        ?.let(employerNames::get)
                        .orEmpty()
                    val place = session.placeLabel.orEmpty()

                    listOf(date, employer, place)
                        .any { it.lowercase(Locale.FRANCE).contains(normalizedQuery) }
                }
            }
            .sortedByDescending { it.realArrivalMs ?: 0L }
            .toList()
    }

    fun format(
        sessions: List<WorkSessionV2>,
        engine: TimeEngineV2,
        nowMs: Long,
        options: Options = Options()
    ): String {
        val dateFormat = SimpleDateFormat("HH:mm", Locale.FRANCE)
        val fullDateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

        return buildString {
            sessions.forEach { session ->
                val countedEntry = WorkTimePolicyV2.repairKnownCountedEntry(
                    session.realArrivalMs,
                    session.countedEntryMs
                )
                val countedEntryWasRepaired =
                    countedEntry != null &&
                        session.countedEntryMs != null &&
                        countedEntry != session.countedEntryMs

                if (options.showEntry) {
                    append("🟢 ")
                        .append(session.realArrivalMs?.let { fullDateFormat.format(Date(it)) } ?: "—")
                        .append("  ENTRÉE RÉELLE\\n")
                    append("⏱ ")
                        .append(countedEntry?.let { fullDateFormat.format(Date(it)) } ?: "À CONFIRMER")
                        .append("  ENTRÉE COMPTÉE")
                    if (countedEntryWasRepaired) append(" (corrigée)")
                    append('\\n')
                }

                session.employerId
                    ?.let(options.employerNames::get)
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append("🏢 ").append(it).append('\\n') }

                session.placeLabel
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append("📍 ").append(it).append('\\n') }

                countedEntry?.let {
                    append("🧭 Créneau retenu : ")
                        .append(shiftLabel(WorkTimePolicyV2.shiftKind(it)))
                        .append('\\n')
                }

                if (options.showPause) {
                    session.pauses.forEachIndexed { index, pause ->
                        append("⏸ Pause ").append(index + 1).append(" : ")
                            .append(dateFormat.format(Date(pause.startMs)))
                            .append(" → ")
                            .append(pause.endMs?.let { dateFormat.format(Date(it)) } ?: "EN COURS")
                            .append(" [")
                            .append(pauseStatus(pause))
                            .append(", ")
                            .append(sourceLabel(pause))
                            .append("]\\n")
                    }
                }

                if (options.showExit) {
                    if (session.realExitMs != null) {
                        append("🔴 ")
                            .append(fullDateFormat.format(Date(session.realExitMs)))
                            .append("  SORTIE RÉELLE\\n")
                        append("⏱ ")
                            .append(session.countedExitMs?.let { fullDateFormat.format(Date(it)) } ?: "À CONFIRMER")
                            .append("  SORTIE COMPTÉE\\n")
                    } else {
                        append("🟢 EN COURS\\n")
                    }
                }

                val result = engine.calculate(session, nowMs)
                val uncertaintyWarnings = result.warnings.filter(::isUncertaintyWarning)
                if (uncertaintyWarnings.isEmpty()) {
                    append("Présence réelle : ").append(formatDuration(result.presenceMs)).append('\\n')
                    append("Temps retenu : ").append(formatDuration(result.countedSpanMs)).append('\\n')
                    append("Pause non payée : ").append(formatDuration(result.unpaidPauseMs)).append('\\n')
                    append("Pause payée : ").append(formatDuration(result.paidPauseMs)).append('\\n')
                    append("Temps payé : ").append(formatDuration(result.paidWorkMs)).append('\\n')
                } else {
                    append("Présence réelle : ")
                        .append(if (result.presenceMs > 0L) formatDuration(result.presenceMs) else "À CONFIRMER")
                        .append('\\n')
                    append("Temps retenu : ")
                        .append(if (result.countedSpanMs > 0L) formatDuration(result.countedSpanMs) else "À CONFIRMER")
                        .append('\\n')
                    append("Temps payé : À CONFIRMER\\n")
                    append("⚠ À confirmer : ")
                        .append(uncertaintyWarnings.joinToString(" ; "))
                        .append('\\n')
                }

                val informationalWarnings = result.warnings.filterNot(::isUncertaintyWarning)
                if (informationalWarnings.isNotEmpty()) {
                    append("ℹ ").append(informationalWarnings.joinToString(" ; ")).append('\\n')
                }
                append('\\n')
            }
        }.ifBlank { options.emptyMessage }
    }

    private fun isUncertaintyWarning(warning: String): Boolean =
        warning.contains("manquante", ignoreCase = true) ||
            warning.contains("à confirmer", ignoreCase = true) ||
            warning.contains("non fiable", ignoreCase = true)

    private fun pauseStatus(pause: PauseV2): String =
        when {
            pause.status != DecisionStatusV2.CONFIRMED || pause.paid == null -> "À CONFIRMER"
            pause.paid -> "PAYÉE"
            else -> "NON PAYÉE"
        }

    private fun sourceLabel(pause: PauseV2): String =
        when (pause.source.name) {
            "GPS" -> "GPS"
            "MANUAL" -> "MANUELLE"
            "IMPORT" -> "IMPORTÉE"
            "SYSTEM" -> "SYSTÈME"
            else -> pause.source.name
        }

    private fun shiftLabel(kind: WorkTimePolicyV2.ShiftKind): String =
        when (kind) {
            WorkTimePolicyV2.ShiftKind.MORNING -> "MATIN"
            WorkTimePolicyV2.ShiftKind.DAY -> "JOURNÉE"
            WorkTimePolicyV2.ShiftKind.AFTERNOON -> "APRÈS-MIDI"
            WorkTimePolicyV2.ShiftKind.NIGHT -> "NUIT"
        }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
        return String.format(Locale.FRANCE, "%02dh %02dm", totalMinutes / 60L, totalMinutes % 60L)
    }
}
