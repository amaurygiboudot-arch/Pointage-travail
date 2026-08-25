package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup

/**
 * Polarisation visuelle du mode automatique jour/nuit.
 * - Nuit : rendu normal, aucune inversion.
 * - Jour : inversion des couleurs de l'interface.
 *
 * L'horloge et les éléments célestes restent volontairement en couleurs naturelles.
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

        contentRoot.setLayerType(
            if (invertInterface) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,
            if (invertInterface) invertPaint else null
        )

        // Une deuxième inversion annule celle du parent : ces vues conservent donc
        // leurs couleurs physiques/naturelles en journée comme la nuit.
        applyCelestialExceptions(contentRoot, invertInterface)
    }

    private fun applyCelestialExceptions(view: View, parentInverted: Boolean) {
        val id = resourceName(view)
        val keepNatural = view is HpAnalogClockView ||
            view is SunIndicatorView ||
            id == "heroClockPermanent" ||
            id == "heroClockHands" ||
            id == "sunIndicator" ||
            id.contains("moon", ignoreCase = true) ||
            id.contains("lune", ignoreCase = true) ||
            id.contains("earth", ignoreCase = true) ||
            id.contains("terre", ignoreCase = true) ||
            id.contains("sun", ignoreCase = true) ||
            id.contains("soleil", ignoreCase = true) ||
            id.contains("celestial", ignoreCase = true)

        if (keepNatural) {
            view.setLayerType(
                if (parentInverted) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,
                if (parentInverted) invertPaint else null
            )
            return
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyCelestialExceptions(view.getChildAt(i), parentInverted)
            }
        }
    }

    private fun resourceName(view: View): String =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
}
