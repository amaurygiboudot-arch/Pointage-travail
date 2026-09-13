package com.amaury.pointage

import android.content.Context
import java.util.Locale

/**
 * Lecture/maintenance des anciennes pauses stockées dans salary_settings.
 *
 * Cette couche ne fournit plus d'interface utilisateur : elle est conservée uniquement pour
 * compatibilité/migration des données historiques et nettoyage des anciennes alarmes.
 */
object CompanyBasePauseSettings {
    private const val PREFS = "salary_settings"

    data class PauseSlot(val startMinute: Int, val endMinute: Int) {
        val durationMinutes: Int
            get() {
                if (startMinute !in 0..1439 || endMinute !in 0..1439 || startMinute == endMinute) return 0
                return (if (endMinute > startMinute) endMinute - startMinute else (24 * 60 - startMinute) + endMinute).coerceIn(0, 240)
            }
    }

    private fun prefix(slot: Int) = if (slot == 2) "company2_" else "company_"
    private fun suffix(pauseIndex: Int) = if (pauseIndex == 2) "2" else ""
    private fun alarmKey(slot: Int, pauseIndex: Int, field: String) = prefix(slot) + "base_pause${suffix(pauseIndex)}_alarm_$field"

    fun startMinute(context: Context, slot: Int, pauseIndex: Int = 1): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(prefix(slot) + "base_pause${suffix(pauseIndex)}_start", -1)

    fun endMinute(context: Context, slot: Int, pauseIndex: Int = 1): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(prefix(slot) + "base_pause${suffix(pauseIndex)}_end", -1)

    fun pause(context: Context, slot: Int, pauseIndex: Int): PauseSlot? =
        PauseSlot(startMinute(context, slot, pauseIndex), endMinute(context, slot, pauseIndex))
            .takeIf { it.durationMinutes > 0 }

    fun baseMinutes(context: Context, slot: Int): Int =
        (1..2).sumOf { pause(context, slot, it)?.durationMinutes ?: 0 }.coerceIn(0, 480)

    fun alarmEnabled(context: Context, slot: Int, pauseIndex: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(alarmKey(slot, pauseIndex, "enabled"), false)

    fun alarmSound(context: Context, slot: Int, pauseIndex: Int): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(alarmKey(slot, pauseIndex, "sound"), "alarm") ?: "alarm"

    fun saveAlarm(context: Context, slot: Int, pauseIndex: Int, enabled: Boolean, sound: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(alarmKey(slot, pauseIndex, "enabled"), enabled)
            .putString(alarmKey(slot, pauseIndex, "sound"), sound)
            .apply()
    }

    fun savePause(context: Context, slot: Int, pauseIndex: Int, startMinute: Int, endMinute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(prefix(slot) + "base_pause${suffix(pauseIndex)}_start", startMinute.coerceIn(0, 1439))
            .putInt(prefix(slot) + "base_pause${suffix(pauseIndex)}_end", endMinute.coerceIn(0, 1439))
            .apply()
    }

    fun clearPause(context: Context, slot: Int, pauseIndex: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(prefix(slot) + "base_pause${suffix(pauseIndex)}_start")
            .remove(prefix(slot) + "base_pause${suffix(pauseIndex)}_end")
            .remove(alarmKey(slot, pauseIndex, "enabled"))
            .remove(alarmKey(slot, pauseIndex, "sound"))
            .apply()
    }

    fun clear(context: Context, slot: Int) {
        clearPause(context, slot, 1)
        clearPause(context, slot, 2)
        CompanyPauseAlarmManager.scheduleAll(context)
    }

    fun label(context: Context, slot: Int): String {
        val pauses = (1..2).mapNotNull { index -> pause(context, slot, index)?.let { index to it } }
        if (pauses.isEmpty()) return "Aucune pause de base"
        val lines = pauses.joinToString("\n") { (index, p) ->
            val alarm = if (alarmEnabled(context, slot, index)) " • 🔔 alarme" else ""
            "Pause $index : ${format(p.startMinute)} – ${format(p.endMinute)} • ${p.durationMinutes} min$alarm"
        }
        return "$lines\nTotal automatiquement déduit : ${baseMinutes(context, slot)} min"
    }

    private fun format(minutes: Int): String =
        String.format(Locale.FRANCE, "%02d:%02d", minutes / 60, minutes % 60)
}
