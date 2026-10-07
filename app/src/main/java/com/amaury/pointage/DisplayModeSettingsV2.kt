package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Context
import android.widget.Toast
import java.util.Locale

/** One entry point for the existing appearance mode and optional local night schedule. */
object DisplayModeSettingsV2 {
    private fun hour(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

    fun label(context: Context): String {
        val profile = PersonalizationStoreV2.read(context)
        if (profile.context == "night") return "MODE : SOMBRE (ANCIEN PROFIL)"
        if (profile.context == "normal" && profile.nightScheduleEnabled)
            return "MODE : SOMBRE DE ${hour(profile.nightStartMinute)} À ${hour(profile.nightEndMinute)}"
        return "MODE : " + when (context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE).getString("mode", "auto")) {
            "light" -> "CLAIR"
            "dark" -> "SOMBRE"
            else -> "AUTOMATIQUE JOUR / NUIT"
        }
    }

    internal fun selectMode(context: Context, owner: String, mode: String): Boolean {
        require(mode in setOf("auto", "light", "dark"))
        if (!PersonalizationStoreV2.update(context, owner) {
                it.copy(context = "normal", reduceMotion = it.effectiveReduceMotion, nightScheduleEnabled = false)
            }) return false
        context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode).apply()
        return true
    }

    internal fun selectSchedule(context: Context, owner: String, start: Int, end: Int): Boolean {
        if (!PersonalizationStoreV2.update(context, owner) {
                it.copy(context = "normal", reduceMotion = it.effectiveReduceMotion,
                    nightScheduleEnabled = true, nightStartMinute = start, nightEndMinute = end)
            }) return false
        // The requested interval is dark; outside it, the palette is light.
        context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE).edit().putString("mode", "light").apply()
        return true
    }

    fun open(activity: Activity, changed: () -> Unit) {
        val owner = PersonalizationStoreV2.accountScope()
        fun sameOwner() = owner == PersonalizationStoreV2.accountScope()
        fun applied(ok: Boolean) {
            if (ok) {
                changed()
                PersonalizationRuntimeV2.refresh()
                AppearanceManager.apply(activity)
                PointageWidgetProvider.refreshAppearance(activity)
                QuickActionsWidgetProvider.refreshAppearance(activity)
            } else Toast.makeText(activity, "Réglages non enregistrés. Vérifie ou restaure le profil de confort.", Toast.LENGTH_LONG).show()
        }
        fun schedule() {
            val profile = PersonalizationStoreV2.read(activity)
            val startPicker = TimePickerDialog(activity, { _, startHour, startMinute ->
                if (sameOwner()) {
                    val endPicker = TimePickerDialog(activity, { _, endHour, endMinute ->
                        if (sameOwner()) {
                            val start = startHour * 60 + startMinute
                            val end = endHour * 60 + endMinute
                            if (start == end) Toast.makeText(activity, "Début et fin doivent être différents", Toast.LENGTH_LONG).show()
                            else applied(selectSchedule(activity, owner, start, end))
                        }
                    }, profile.nightEndMinute / 60, profile.nightEndMinute % 60, true)
                    endPicker.setTitle("Fin du mode sombre — retour au mode clair")
                    endPicker.show(); PersonalizationRuntimeV2.track(endPicker)
                }
            }, profile.nightStartMinute / 60, profile.nightStartMinute % 60, true)
            startPicker.setTitle("Début du mode sombre — heure locale")
            startPicker.show(); PersonalizationRuntimeV2.track(startPicker)
        }
        val appearance = activity.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val title = if (appearance.getBoolean("custom_bg", false) || appearance.getBoolean("custom_image_bg", false))
            "Palette — ton fond personnalisé reste conservé" else "Mode d’affichage"
        PersonalizationRuntimeV2.track(AlertDialog.Builder(activity).setTitle(title)
            .setItems(arrayOf("Automatique selon le soleil", "Clair", "Sombre", "Sombre à heures fixes…")) { _, which ->
                if (sameOwner()) {
                    if (which == 3) schedule()
                    else applied(selectMode(activity, owner, arrayOf("auto", "light", "dark")[which]))
                }
            }.setNegativeButton("Annuler", null).show())
    }
}
