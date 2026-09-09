package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.engine.WorkTimePolicyV2
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
     * Détection automatique indicative par heure d'embauche comptée.
     * En V2, WorkTimePolicyV2 reste la source unique de classification des postes.
     * V1 conserve son ancienne détection tant qu'elle reste présente dans le dépôt.
     */
    fun detect(entryMs: Long): ShiftType {
        if (HoraTrackV2.ENABLED) {
            return when (WorkTimePolicyV2.shiftKind(entryMs)) {
                WorkTimePolicyV2.ShiftKind.MORNING -> ShiftType.MORNING
                WorkTimePolicyV2.ShiftKind.DAY -> ShiftType.DAY
                WorkTimePolicyV2.ShiftKind.AFTERNOON -> ShiftType.AFTERNOON
                WorkTimePolicyV2.ShiftKind.NIGHT -> ShiftType.NIGHT
            }
        }
        val cal = Calendar.getInstance(Locale.FRANCE).apply { timeInMillis = entryMs }
        return when (cal.get(Calendar.HOUR_OF_DAY)) {
            6 -> ShiftType.MORNING
            in 7..11 -> ShiftType.DAY
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

    /**
     * Compatibilité V1 uniquement. En V2, ce booléen ne peut pas représenter l'état juridique
     * « à confirmer » et ne doit donc jamais accorder automatiquement un panier à partir du poste.
     * Le droit et le montant sont résolus par le moteur repas V2 vérifié.
     */
    fun mealEnabled(context: Context, shift: ShiftType): Boolean {
        if (HoraTrackV2.ENABLED) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val default = shift == ShiftType.MORNING
        return prefs.getBoolean("meal_${shift.id}", default)
    }

    fun setMealEnabled(context: Context, shift: ShiftType, enabled: Boolean) {
        if (HoraTrackV2.ENABLED) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("meal_${shift.id}", enabled).apply()
    }
}

/**
 * Ancienne table locale de règles conventionnelles de nuit.
 *
 * Elle reste comme point de compatibilité tant que les anciens appels existent, mais ne renvoie plus
 * aucune valeur : une majoration de nuit ne doit plus provenir d'un IDCC codé en dur. La future règle
 * calculable doit venir d'une source officielle datée (ACCO/KALI), avec sa plage horaire et ses
 * conditions explicitement structurées.
 */
object ConventionNightRules {
    data class Rule(val startMinute: Int, val endMinute: Int, val premiumMultiplier: Double, val note: String)

    @Suppress("UNUSED_PARAMETER")
    fun forIdcc(idcc: String?): Rule? = null
}
