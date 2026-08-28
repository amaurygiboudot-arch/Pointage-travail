package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.amaury.pointage.v2.ui.V2LegacyIsolationUi
import com.amaury.pointage.v2.ui.V2TestUiInstaller

/**
 * Le widget suit désormais uniquement le thème de l'application.
 * On conserve seulement le réglage « Afficher la position dans le widget ».
 * Ce View sert aussi de point d'accroche discret au panneau de test HoraTrack V2.
 */
class WidgetSettingsCleanerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val cleanup = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            hideLegacyWidgetControls(rootView)
            (context as? Activity)?.let { V2LegacyIsolationUi.refresh(it) }
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
        (context as? Activity)?.let { activity ->
            post {
                V2TestUiInstaller.install(activity)
                V2LegacyIsolationUi.refresh(activity)
            }
        }
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
