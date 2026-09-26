package com.amaury.pointage.v2.engine

import com.amaury.pointage.v2.model.WorkSessionV2
import java.util.Calendar
import java.util.Locale

/**
 * Garde-fou des seuils hebdomadaires lorsque le mois coupe une semaine ISO.
 *
 * Le calcul mensuel historique découpe les sessions aux bornes du mois. Or les heures
 * supplémentaires/complémentaires restent des mécanismes hebdomadaires : des minutes du mois
 * précédent ou suivant peuvent donc changer la qualification des minutes du mois courant.
 *
 * Cette couche ne réalloue aucun euro. Elle détecte uniquement les semaines de bord pour
 * lesquelles le contexte mensuel tronqué n'est pas suffisant et rend alors le brut non fiable.
 */
object WeeklyThresholdMonthBoundaryGuardV2 {
    const val CONTEXT_WARNING =
        "Seuil hebdomadaire : une semaine chevauche deux mois et dépasse le seuil avec du temps payé hors du mois ; la répartition des heures supplémentaires/complémentaires du mois reste à confirmer."
    const val FUTURE_CONTEXT_WARNING =
        "Seuil hebdomadaire : la semaine de fin de mois n'est pas encore terminée ; le contexte hebdomadaire complet n'est pas disponible."
    const val SOURCE_WARNING =
        "Seuil hebdomadaire : historique de pointage non fiable sur une semaine de bord ; qualification hebdomadaire bloquée."

    data class BoundaryWeek(
        val weekYear: Int,
        val weekOfYear: Int,
        val fullWeekPaidMinutes: Int,
        val inMonthPaidMinutes: Int
    )

    data class Result(
        val reliable: Boolean,
        val affectedWeeks: List<BoundaryWeek>,
        val warnings: List<String>
    )

    fun assess(
        sessions: List<WorkSessionV2>,
        acceptedEmployerIds: Set<String>,
        rangeStartMs: Long,
        rangeEndMs: Long,
        weeklyThresholdMinutes: Int,
        sourceReliable: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Result {
        val ids = acceptedEmployerIds.map(String::trim).filter(String::isNotEmpty).toSet()
        if (rangeEndMs <= rangeStartMs || weeklyThresholdMinutes <= 0 || ids.isEmpty()) {
            return Result(
                reliable = false,
                affectedWeeks = emptyList(),
                warnings = listOf(SOURCE_WARNING)
            )
        }
        if (!sourceReliable) {
            return Result(
                reliable = false,
                affectedWeeks = emptyList(),
                warnings = listOf(SOURCE_WARNING)
            )
        }

        val boundaryIntervals = listOf(
            isoWeekInterval(rangeStartMs),
            isoWeekInterval(rangeEndMs - 1L)
        ).distinctBy { it.first to it.second }

        var reliable = true
        val affected = mutableListOf<BoundaryWeek>()
        val warnings = mutableListOf<String>()
        val thresholdMs = weeklyThresholdMinutes.toLong() * 60_000L

        for ((weekStart, weekEnd) in boundaryIntervals) {
            if (weekStart >= rangeStartMs && weekEnd <= rangeEndMs) continue

            if (weekEnd > nowMs) {
                reliable = false
                warnings += FUTURE_CONTEXT_WARNING
                continue
            }

            val scope = MonthlyPaidWorkScopeV2.resolve(
                sessions = sessions,
                acceptedEmployerIds = ids,
                rangeStartMs = weekStart,
                rangeEndMs = weekEnd,
                nowMs = nowMs
            )
            if (!scope.reliable) {
                reliable = false
                warnings += SOURCE_WARNING
                warnings += scope.warnings
                continue
            }

            var fullPaidMs = 0L
            var inMonthPaidMs = 0L
            val monthSliceStart = maxOf(weekStart, rangeStartMs)
            val monthSliceEnd = minOf(weekEnd, rangeEndMs)

            for (session in scope.selected) {
                fullPaidMs += PaidWorkAllocationV2.paidOverlap(session, weekStart, weekEnd)
                if (monthSliceEnd > monthSliceStart) {
                    inMonthPaidMs += PaidWorkAllocationV2.paidOverlap(
                        session,
                        monthSliceStart,
                        monthSliceEnd
                    )
                }
            }

            val outsidePaidMs = (fullPaidMs - inMonthPaidMs).coerceAtLeast(0L)
            if (fullPaidMs > thresholdMs && outsidePaidMs > 0L) {
                reliable = false
                val cal = calendar(weekStart)
                affected += BoundaryWeek(
                    weekYear = cal.weekYear,
                    weekOfYear = cal.get(Calendar.WEEK_OF_YEAR),
                    fullWeekPaidMinutes = (fullPaidMs / 60_000L).toInt(),
                    inMonthPaidMinutes = (inMonthPaidMs / 60_000L).toInt()
                )
                warnings += CONTEXT_WARNING
            }
        }

        return Result(
            reliable = reliable,
            affectedWeeks = affected,
            warnings = warnings.distinct()
        )
    }

    private fun isoWeekInterval(atMs: Long): Pair<Long, Long> {
        val cal = calendar(atMs)
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val backToMonday = if (day == Calendar.SUNDAY) 6 else day - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_YEAR, -backToMonday)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 7)
        return start to cal.timeInMillis
    }

    private fun calendar(ms: Long) = Calendar.getInstance(Locale.FRANCE).apply {
        firstDayOfWeek = Calendar.MONDAY
        minimalDaysInFirstWeek = 4
        timeInMillis = ms
    }
}
