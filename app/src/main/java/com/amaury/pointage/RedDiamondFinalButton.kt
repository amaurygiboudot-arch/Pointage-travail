package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.Button

/**
 * Zone tactile transparente du bouton SORTIE.
 * Le visuel des boutons de pointage est rendu uniquement par OpenGL 3D.
 */
open class RedDiamondFinalButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : Button(context, attrs, defStyleAttr) {

    companion object {
        const val RENDER_NAME = "OpenGL 3D only"

        fun updateGlobalNaturalLight(
            angle: Float,
            pitch: Float,
            roll: Float,
            intensity: Float,
            night: Boolean,
            elevation: Float
        ) = Unit
    }

    init {
        background = null
        backgroundTintList = null
        stateListAnimator = null
        elevation = 0f
        translationZ = 0f
        setPadding(0, 0, 0, 0)
        isAllCaps = false
        text = ""
    }

    override fun onDraw(canvas: Canvas) = Unit
}
