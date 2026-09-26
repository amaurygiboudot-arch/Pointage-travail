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
    const val TAG_PERSONALIZATION = "settings_personalization_installed"

    fun panel(activity: MainActivity): LinearLayout? =
        activity.findViewById(R.id.gpsSettingsPanel)

    fun personalization(activity: MainActivity): LinearLayout? =
        panel(activity)?.findViewWithTag<View>(TAG_PERSONALIZATION) as? LinearLayout

    fun contains(activity: MainActivity, tag: String): Boolean =
        panel(activity)?.findViewWithTag<View>(tag) != null
}
