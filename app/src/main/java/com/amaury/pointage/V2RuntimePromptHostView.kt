package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.amaury.pointage.v2.ui.V2GpsPromptController
import com.amaury.pointage.v2.ui.V2PauseEndPromptController
import com.amaury.pointage.v2.ui.V2TestUiInstaller

/**
 * Hôte du cycle de vie des confirmations V2 affichées pendant que l'activité est ouverte.
 * Il ne modifie aucun réglage et n'inspecte pas la hiérarchie de vues.
 */
class V2RuntimePromptHostView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val poll = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            showPendingPrompts()
            postDelayed(this, 1000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(poll)
        (context as? Activity)?.let { activity ->
            post {
                V2TestUiInstaller.install(activity)
                showPendingPrompts()
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(poll)
        super.onDetachedFromWindow()
    }

    private fun showPendingPrompts() {
        (context as? Activity)?.let { activity ->
            V2PauseEndPromptController.maybeShow(activity)
            V2GpsPromptController.maybeShow(activity)
        }
    }
}
