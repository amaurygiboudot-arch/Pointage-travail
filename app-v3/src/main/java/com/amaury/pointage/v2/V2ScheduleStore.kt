package com.amaury.pointage.v2

import android.content.Context
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** Horaires explicites de profil. Aucun horaire par défaut n'est inventé. */
object V2ScheduleStore {
    private const val PREFS = "shift_profiles"
    private const val KEY_MODE = "selected_shift"
    val SHIFT_IDS = listOf("morning", "day", "afternoon", "night")

    data class Schedule(val id:String, val startMinute:Int?, val endMinute:Int?)
    data class Match(val schedule:Schedule, val expectedStartMs:Long?, val expectedEndMs:Long, val scoreMs:Long)

    fun schedule(context: Context, id: String): Schedule {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Schedule(
            id = id,
            startMinute = parseMinute(p.getString("expected_start_$id", "").orEmpty()),
            endMinute = parseMinute(p.getString("expected_end_$id", "").orEmpty())
        )
    }

    fun save(context: Context, id: String, start: String?, end: String?) {
        require(id in SHIFT_IDS)
        val startMin = start?.let(::parseMinute)
        val endMin = end?.let(::parseMinute)
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (start.isNullOrBlank()) editor.remove("expected_start_$id")
        else requireNotNull(startMin) { "Heure de début invalide" }.also { editor.putString("expected_start_$id", formatMinute(it)) }
        if (end.isNullOrBlank()) editor.remove("expected_end_$id")
        else requireNotNull(endMin) { "Heure de fin invalide" }.also { editor.putString("expected_end_$id", formatMinute(it)) }
        editor.apply()
    }

    fun selectedMode(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "auto") ?: "auto"

    /** Fin prévue utilisable dès l'entrée uniquement si le profil est choisi explicitement. */
    fun expectedEndForEntry(context: Context, entryMs: Long): Long? {
        val mode = selectedMode(context)
        if (mode !in SHIFT_IDS) return null
        return expectedEnd(schedule(context, mode), entryMs)
    }

    /**
     * En automatique, compare l'entrée ET la sortie réelles aux profils explicitement configurés.
     * Un profil sans fin prévue n'est jamais utilisé pour la règle de sortie +20 min.
     */
    fun bestMatch(context: Context, entryMs: Long, exitMs: Long): Match? {
        if (exitMs <= entryMs) return null
        val mode = selectedMode(context)
        val candidates = if (mode in SHIFT_IDS) listOf(schedule(context, mode)) else SHIFT_IDS.map { schedule(context, it) }
        return candidates.mapNotNull { s ->
            val end = expectedEnd(s, entryMs) ?: return@mapNotNull null
            val start = s.startMinute?.let { occurrenceNearEntry(entryMs, it) }
            val entryScore = start?.let { abs(entryMs - it) } ?: 0L
            val exitScore = abs(exitMs - end)
            Match(s, start, end, entryScore + exitScore)
        }.minByOrNull { it.scoreMs }
    }

    fun expectedEnd(context: Context, entryMs: Long, exitMs: Long): Long? =
        expectedEndForEntry(context, entryMs) ?: bestMatch(context, entryMs, exitMs)?.expectedEndMs

    private fun expectedEnd(schedule: Schedule, entryMs: Long): Long? {
        val endMinute = schedule.endMinute ?: return null
        val end = occurrenceNearEntry(entryMs, endMinute)
        // Si l'heure de fin du calendrier est avant l'entrée, elle appartient au lendemain.
        return if (end <= entryMs) end + dayLengthAround(end) else end
    }

    private fun occurrenceNearEntry(entryMs: Long, minuteOfDay: Int): Long {
        return Calendar.getInstance(Locale.FRANCE).apply {
            timeInMillis = entryMs
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun dayLengthAround(ms: Long): Long {
        val a = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = ms }
        val b = (a.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        return b.timeInMillis - a.timeInMillis
    }

    fun parseMinute(value: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})[:hH](\\d{2})\\s*$").matchEntire(value) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    fun formatMinute(minute: Int): String = String.format(Locale.FRANCE, "%02d:%02d", minute / 60, minute % 60)
}
