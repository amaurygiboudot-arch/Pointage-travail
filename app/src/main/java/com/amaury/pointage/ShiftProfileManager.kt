package com.amaury.pointage

import android.content.Context
import java.util.Calendar
import java.util.Locale

enum class ShiftType(val id: String, val label: String) {
    MORNING("morning", "Matin"),
    DAY("day", "Journée"),
    AFTERNOON("afternoon", "Après-midi"),
    NIGHT("night", "Nuit")
}

object ShiftProfileManager {
    private const val PREFS = "shift_profiles"
    private const val KEY_MODE = "selected_shift"

    fun selectedMode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "auto") ?: "auto"

    fun setSelectedMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
    }

    /**
     * Détection automatique indicative par heure d'embauche.
     * Le choix manuel du poste reste prioritaire, car les plages autorisées
     * des équipes peuvent se chevaucher (notamment matin / journée / après-midi).
     */
    fun detect(entryMs: Long): ShiftType {
        val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entryMs }
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            in 6..7 -> ShiftType.MORNING
            in 8..11 -> ShiftType.DAY
            in 12..20 -> ShiftType.AFTERNOON
            else -> ShiftType.NIGHT
        }
    }

    fun resolve(context: Context, entryMs: Long): ShiftType {
        return when (selectedMode(context)) {
            ShiftType.MORNING.id -> ShiftType.MORNING
            ShiftType.DAY.id -> ShiftType.DAY
            ShiftType.AFTERNOON.id -> ShiftType.AFTERNOON
            ShiftType.NIGHT.id -> ShiftType.NIGHT
            else -> detect(entryMs)
        }
    }

    fun pauseMinutes(context: Context, shift: ShiftType): Int {
        val defaultMinutes = when (shift) {
            ShiftType.DAY -> 60
            ShiftType.NIGHT -> 30
            else -> 0
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("pause_${shift.id}", defaultMinutes).coerceIn(0, 240)
    }

    fun setPauseMinutes(context: Context, shift: ShiftType, minutes: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("pause_${shift.id}", minutes.coerceIn(0, 240)).apply()
    }

    fun mealEnabled(context: Context, shift: ShiftType): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val default = shift == ShiftType.MORNING
        return prefs.getBoolean("meal_${shift.id}", default)
    }

    fun setMealEnabled(context: Context, shift: ShiftType, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("meal_${shift.id}", enabled).apply()
    }
}

/** Règles de nuit intégrées uniquement lorsqu'elles sont suffisamment connues. */
object ConventionNightRules {
    data class Rule(val startMinute: Int, val endMinute: Int, val premiumMultiplier: Double, val note: String)

    fun forIdcc(idcc: String?): Rule? = when (idcc?.trim()?.padStart(4, '0')) {
        "0292" -> Rule(
            startMinute = 21 * 60,
            endMinute = 6 * 60,
            premiumMultiplier = 1.12,
            note = "Plasturgie : référence 21h–6h ; l'horaire de nuit pratiqué dans l'entreprise peut s'appliquer."
        )
        else -> null
    }
}
