package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * Nettoie les anciens réglages du widget et harmonise les libellés dynamiques
 * installés dans les paramètres.
 */
class WidgetSettingsCleanerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cleanup = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            cleanDynamicSettings(rootView)
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

    private fun cleanDynamicSettings(view: View) {
        if (view is TextView) {
            val current = view.text?.toString()?.trim().orEmpty()
            when (current.uppercase()) {
                "PERSONNALISER LE WIDGET",
                "COULEUR DU FOND DU WIDGET",
                "COULEUR D'ACCENT DU WIDGET" -> view.visibility = GONE
                "SAUVEGARDE GOOGLE DRIVE" -> view.text = "EXPORT PDF AUTOMATIQUE — GOOGLE DRIVE"
            }
            if (current.startsWith("● Sauvegarde Drive active")) {
                view.text = current.replaceFirst("Sauvegarde Drive active", "Export PDF Drive actif")
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) cleanDynamicSettings(view.getChildAt(i))
        }
    }
}
