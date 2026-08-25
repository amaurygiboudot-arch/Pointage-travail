package com.amaury.pointage

import android.app.Activity
import android.os.Build
import android.view.View
import android.widget.Button

/** Barrière permanente autour des trois boutons principaux de pointage. */
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
            button.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> restore(v as Button) }
            button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = restore(v as Button)
                override fun onViewDetachedFromWindow(v: View) = Unit
            })
        }
        restore(button)
        button.post { restore(button) }
    }

    private fun restore(button: Button) {
        button.backgroundTintList = null
        button.background = null
        button.alpha = 1f
        // Un moteur de thème/rafraîchissement ne doit jamais pouvoir laisser
        // les trois commandes principales invisibles ou masquées.
        if (button.visibility != View.VISIBLE) button.visibility = View.VISIBLE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.stateListAnimator = null
            button.elevation = 0f
            button.translationZ = 0f
        }
        if (!button.isPressed) {
            if (button.scaleX != 1f) button.scaleX = 1f
            if (button.scaleY != 1f) button.scaleY = 1f
        }
        if (button.layerType != View.LAYER_TYPE_NONE) button.setLayerType(View.LAYER_TYPE_NONE, null)
    }
}
