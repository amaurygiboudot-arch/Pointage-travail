package com.amaury.pointage

import android.app.Activity
import android.os.Build
import android.view.View
import android.widget.Button

/**
 * Barrière de sécurité autour des trois boutons de pointage principaux.
 * Les anciens moteurs de thème/relief ne doivent jamais modifier leur rendu.
 */
object PrimaryButtonIsolation {
    private const val TAG = 0x50424953

    fun install(activity: Activity) {
        listOf(
            activity.findViewById<Button>(R.id.entryButton),
            activity.findViewById<Button>(R.id.pauseButton),
            activity.findViewById<Button>(R.id.exitButton)
        ).filterNotNull().forEach(::protect)
    }

    private fun protect(button: Button) {
        if (button.getTag(TAG) != true) {
            button.setTag(TAG, true)
            button.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                restore(v as Button)
            }
            button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = restore(v as Button)
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
        restore(button)
        button.post { restore(button) }
    }

    private fun restore(button: Button) {
        // Le moteur Canvas des FinalButton dessine tout lui-même.
        button.backgroundTintList = null
        button.background = null
        button.alpha = 1f

        // Aucun ancien StateListAnimator/elevation ne doit reprendre la main.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.stateListAnimator = null
            button.elevation = 0f
            button.translationZ = 0f
        }

        // Évite un bouton laissé rétréci après une animation interrompue.
        if (!button.isPressed) {
            if (button.scaleX != 1f) button.scaleX = 1f
            if (button.scaleY != 1f) button.scaleY = 1f
        }

        // Les filtres jour/nuit ne doivent jamais englober ces vues.
        if (button.layerType != View.LAYER_TYPE_NONE) {
            button.setLayerType(View.LAYER_TYPE_NONE, null)
        }
    }
}
