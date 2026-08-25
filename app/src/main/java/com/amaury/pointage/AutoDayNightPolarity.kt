package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/**
 * Polarisation visuelle du mode automatique jour/nuit.
 * - Nuit : rendu normal, aucune inversion.
 * - Jour : inversion des zones statiques de l'interface.
 *
 * IMPORTANT : aucun filtre plein écran. Cela évite de recomposer toute l'app à
 * chaque invalidation des boutons diamant/capteurs.
 */
object AutoDayNightPolarity {
    private val invertPaint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    // Mémorise l'état déjà appliqué pour ne pas recréer des couches GPU à
    // chaque layout global, changement d'onglet ou rafraîchissement de texte.
    private val appliedState = WeakHashMap<View, Boolean>()

    private val protectedIds = setOf(
        "entryButton", "pauseButton", "exitButton",
        "heroClockPermanent", "heroClockHands", "sunIndicator"
    )

    fun apply(anchor: View) {
        val context = anchor.context
        val prefs = context.getSharedPreferences(AppThemeCatalog.PREFS, Context.MODE_PRIVATE)
        val automatic = (prefs.getString("mode", "auto") ?: "auto") == "auto"
        val invertInterface = automatic && !AppThemeCatalog.useDarkPalette(context)

        val contentRoot = (context as? Activity)
            ?.window
            ?.decorView
            ?.findViewById<ViewGroup>(android.R.id.content)
            ?: (anchor.rootView as? ViewGroup)
            ?: return

        // Annule explicitement tout ancien filtre plein écran issu des versions
        // précédentes : c'était la principale source de latence.
        if (contentRoot.layerType != View.LAYER_TYPE_NONE) {
            contentRoot.setLayerType(View.LAYER_TYPE_NONE, null)
        }

        applyTargeted(contentRoot, invertInterface)
    }

    private fun applyTargeted(view: View, inverted: Boolean) {
        val id = resourceName(view)

        // Les boutons de pointage, l'horloge et les objets célestes gardent
        // toujours leurs vraies couleurs et ne reçoivent jamais de couche GPU.
        if (isNaturalView(view, id)) {
            setInversion(view, false)
            return
        }

        // On polarise quelques blocs stables au lieu du décor complet. Les
        // animations des diamants ne forcent ainsi plus la recomposition totale.
        if (shouldInvertAsBlock(id)) {
            setInversion(view, inverted)
            return
        }

        // Dans la zone des boutons, on descend pour inverser seulement les
        // libellés/contrôles statiques, jamais les trois diamants.
        if (id == "pointageButtons" && view is ViewGroup) {
            for (i in 0 until view.childCount) applyPointageChildren(view.getChildAt(i), inverted)
            return
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTargeted(view.getChildAt(i), inverted)
        }
    }

    private fun applyPointageChildren(view: View, inverted: Boolean) {
        val id = resourceName(view)
        if (isNaturalView(view, id)) {
            setInversion(view, false)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyPointageChildren(view.getChildAt(i), inverted)
        } else {
            setInversion(view, inverted)
        }
    }

    private fun shouldInvertAsBlock(id: String): Boolean =
        id == "navigationTabs" ||
        id == "statusCard" ||
        id == "contentPanel" ||
        id == "shiftControlView"

    private fun isNaturalView(view: View, id: String): Boolean =
        id in protectedIds ||
        view is HpAnalogClockView ||
        view is SunIndicatorView ||
        view is RedDiamondFinalButton ||
        view is GreenDiamondFinalButton ||
        view is OrangeDiamondFinalButton ||
        id.contains("moon", ignoreCase = true) ||
        id.contains("lune", ignoreCase = true) ||
        id.contains("earth", ignoreCase = true) ||
        id.contains("terre", ignoreCase = true) ||
        id.contains("sun", ignoreCase = true) ||
        id.contains("soleil", ignoreCase = true) ||
        id.contains("celestial", ignoreCase = true)

    private fun setInversion(view: View, inverted: Boolean) {
        if (appliedState[view] == inverted) return
        appliedState[view] = inverted
        view.setLayerType(
            if (inverted) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,
            if (inverted) invertPaint else null
        )
    }

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
}
