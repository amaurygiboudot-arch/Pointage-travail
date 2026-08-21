package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Le widget suit désormais uniquement le thème de l'application.
 * On conserve seulement le réglage « Afficher la position dans le widget ».
 */
class WidgetSettingsCleanerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cleanup = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            hideLegacyWidgetControls(rootView)
            postDelayed(this, 600L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.getSharedPreferences("widget_style", Context.MODE_PRIVATE)
            .edit()
            .remove("widget_bg")
            .remove("widget_accent")
            .apply()
        post(cleanup)
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(cleanup)
        super.onDetachedFromWindow()
    }

    private fun hideLegacyWidgetControls(view: View) {
        if (view is TextView) {
            when (view.text?.toString()?.trim()?.uppercase()) {
                "PERSONNALISER LE WIDGET",
                "COULEUR DU FOND DU WIDGET",
                "COULEUR D'ACCENT DU WIDGET" -> view.visibility = GONE
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) hideLegacyWidgetControls(view.getChildAt(i))
        }
    }
}
