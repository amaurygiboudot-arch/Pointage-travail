package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap

/** Polarisation visuelle ciblée du mode automatique jour/nuit. */
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
        val contentRoot = (context as? Activity)?.window?.decorView?.findViewById<ViewGroup>(android.R.id.content)
            ?: (anchor.rootView as? ViewGroup) ?: return
        if (contentRoot.layerType != View.LAYER_TYPE_NONE) contentRoot.setLayerType(View.LAYER_TYPE_NONE, null)
        applyTargeted(contentRoot, invertInterface)
    }

    private fun applyTargeted(view: View, inverted: Boolean) {
        val id = resourceName(view)
        if (isNaturalView(view, id)) {
            setInversion(view, false)
            return
        }
        if (shouldInvertAsBlock(id)) {
            setInversion(view, inverted)
            return
        }
        if (id == "pointageButtons" && view is ViewGroup) {
            for (i in 0 until view.childCount) applyPointageChildren(view.getChildAt(i), inverted)
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) applyTargeted(view.getChildAt(i), inverted)
    }

    private fun applyPointageChildren(view: View, inverted: Boolean) {
        val id = resourceName(view)
        if (isNaturalView(view, id)) {
            setInversion(view, false)
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) applyPointageChildren(view.getChildAt(i), inverted)
        else setInversion(view, inverted)
    }

    private fun shouldInvertAsBlock(id: String): Boolean =
        id == "navigationTabs" || id == "statusCard" || id == "contentPanel" || id == "shiftControlView"

    private fun isNaturalView(view: View, id: String): Boolean =
        StandardButtonLiveStyle.isLiveManaged(view) ||
        id in protectedIds ||
        view is HpAnalogClockView ||
        view is SunIndicatorView ||
        view is RedDiamondFinalButton ||
        view is GreenDiamondFinalButton ||
        view is OrangeDiamondFinalButton ||
        id.contains("moon", true) || id.contains("lune", true) ||
        id.contains("earth", true) || id.contains("terre", true) ||
        id.contains("sun", true) || id.contains("soleil", true) || id.contains("celestial", true)

    private fun setInversion(view: View, inverted: Boolean) {
        if (appliedState[view] == inverted) return
        appliedState[view] = inverted
        view.setLayerType(if (inverted) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, if (inverted) invertPaint else null)
    }

    private fun resourceName(view: View): String = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull().orEmpty()
}
