package com.amaury.pointage

import android.view.View
import android.widget.TextView

/**
 * Réorganise uniquement les enfants directs du panneau Paramètres.
 *
 * Aucun store, aucune préférence et aucune règle métier n'est modifié ici :
 * les vues existantes sont conservées et seulement remises dans un ordre
 * utilisateur stable.
 */
object SettingsV2SectionOrganizer {
    fun organize(activity: MainActivity) {
        val panel = SettingsV2Host.panel(activity) ?: return
        if (panel.childCount < 2) return

        val children = (0 until panel.childCount).map { index ->
            val view = panel.getChildAt(index)
            Triple(priority(view), index, view)
        }.sortedWith(compareBy<Triple<Int, Int, View>> { it.first }.thenBy { it.second })

        if (children.map { it.third } == (0 until panel.childCount).map(panel::getChildAt)) return

        panel.removeAllViews()
        children.forEach { panel.addView(it.third) }
    }

    private fun priority(view: View): Int {
        val tag = view.tag?.toString().orEmpty()
        val id = resourceName(view)

        return when {
            view is FirebaseAccountButtonView -> 10
            view is V2SecuritySettingsView || tag == V2SecuritySettingsView.TAG -> 11

            isPointageView(view, id, tag) -> 20

            isCelestialView(view, id) -> 30
            tag == SettingsV2Host.TAG_PERSONALIZATION -> 31
            tag == SettingsV2Host.TAG_WIDGET -> 32

            view is V2BackupRestoreView || tag == V2BackupRestoreView.TAG -> 40
            tag == SettingsV2Host.TAG_DRIVE -> 41
            tag == SettingsV2Host.TAG_UPDATES -> 45

            tag == SettingsV2Host.TAG_HELP -> 50
            view is SuggestionBoxView -> 51
            tag == "first_steps_replay" -> 52
            view is SnakeGameButtonView -> 53

            view is V2RuntimePromptHostView -> 90
            else -> 70
        }
    }

    private fun isPointageView(view: View, id: String, tag: String): Boolean {
        if (view is LocationManagementView || view is GpsPointPickerView || view is GpsZoneTypeView) return true
        if (tag == GpsZoneTypeView.TAG) return true
        if (id in setOf(
                "workplaceAddress",
                "geofenceRadius",
                "autoGpsSwitch",
                "gpsStatusText",
                "saveGpsSettingsButton",
                "gpsPointPickerView",
                "locationPermissionButton",
                "locationManagementView"
            )
        ) return true
        return (view as? TextView)?.text?.toString()?.trim()?.uppercase() in setOf(
            "POINTAGE GPS",
            "RAYON DE DÉCLENCHEMENT (MÈTRES)"
        )
    }

    private fun isCelestialView(view: View, id: String): Boolean {
        if (id == "celestialGlobeModeGroup" || id == "celestialWeatherAttribution") return true
        val text = (view as? TextView)?.text?.toString()?.trim().orEmpty()
        return text == "SYSTÈME CÉLESTE" ||
            text == "Mode du globe terrestre" ||
            text.startsWith("Local : centre le globe")
    }

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
}
