package com.amaury.pointage

import android.view.View
import android.view.ViewGroup

/**
 * Compatibilité avec les anciennes versions qui inversaient les couleurs de
 * l'interface en mode jour automatique.
 *
 * L'inversion visuelle est désormais totalement désactivée : l'application
 * conserve toujours les couleurs définies par le thème, en jour comme en nuit.
 * apply() sert uniquement à retirer d'éventuelles couches GPU d'inversion qui
 * seraient encore présentes sur des vues déjà créées.
 */
object AutoDayNightPolarity {
    fun apply(anchor: View) {
        clearLayers(anchor.rootView)
    }

    private fun clearLayers(view: View) {
        if (view.layerType != View.LAYER_TYPE_NONE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) clearLayers(view.getChildAt(i))
        }
    }
}
