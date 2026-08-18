package com.amaury.pointage

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.StateListAnimator
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button

/**
 * Donne un relief discret à tous les boutons sans modifier leurs couleurs
 * ni leurs arrière-plans. Les boutons créés dynamiquement sont également pris
 * en charge grâce au listener de layout.
 */
object ButtonReliefInstaller {
    private const val TAG_KEY = 0x4850524C // "HPRL"

    fun install(activity: Activity) {
        val decor = activity.window.decorView
        applyToTree(decor)
        decor.viewTreeObserver.addOnGlobalLayoutListener {
            if (!activity.isFinishing && !activity.isDestroyed) {
                applyToTree(decor)
            }
        }
    }

    private fun applyToTree(view: View) {
        if (view is Button) applyToButton(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyToTree(view.getChildAt(i))
        }
    }

    private fun applyToButton(button: Button) {
        if (button.getTag(TAG_KEY) == true) return
        button.setTag(TAG_KEY, true)

        val density = button.resources.displayMetrics.density
        val normalElevation = 4f * density
        val pressedElevation = 1.5f * density
        val normalTranslation = 1f * density

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            button.elevation = normalElevation
            button.stateListAnimator = StateListAnimator().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(button, "elevation", pressedElevation),
                            ObjectAnimator.ofFloat(button, "translationZ", 0f),
                            ObjectAnimator.ofFloat(button, "scaleX", 0.985f),
                            ObjectAnimator.ofFloat(button, "scaleY", 0.985f)
                        )
                        duration = 80L
                    }
                )
                addState(
                    intArrayOf(),
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(button, "elevation", normalElevation),
                            ObjectAnimator.ofFloat(button, "translationZ", normalTranslation),
                            ObjectAnimator.ofFloat(button, "scaleX", 1f),
                            ObjectAnimator.ofFloat(button, "scaleY", 1f)
                        )
                        duration = 120L
                    }
                )
            }
        }
    }
}
