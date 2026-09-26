package com.amaury.pointage

import android.view.View
import android.widget.LinearLayout

/**
 * Point d'entrée unique de l'interface Paramètres V2.
 *
 * Ce composant ne possède aucune règle métier : il centralise uniquement
 * l'accès aux conteneurs UI afin d'éviter les tags et recherches parallèles
 * dispersés entre les installateurs historiques.
 */
object SettingsV2Host {
    const val TAG_UPDATES = "settings_updates_v2"
    const val TAG_PERSONALIZATION = "settings_personalization_installed"
    const val TAG_WIDGET = "settings_widget_v2"
    const val TAG_DRIVE = "settings_drive_v2"
    const val TAG_HELP = "settings_help_v2"

    fun panel(activity: MainActivity): LinearLayout? =
        activity.findViewById(R.id.gpsSettingsPanel)

    fun section(activity: MainActivity, tag: String): LinearLayout? =
        panel(activity)?.findViewWithTag<View>(tag) as? LinearLayout

    fun personalization(activity: MainActivity): LinearLayout? =
        section(activity, TAG_PERSONALIZATION)

    fun contains(activity: MainActivity, tag: String): Boolean =
        panel(activity)?.findViewWithTag<View>(tag) != null
}
