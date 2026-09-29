package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/** Launches the 2D campaign from the existing Aide & extras section. */
class ObjectiveDeliveryGameButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    init {
        text = "OBJECTIF LIVRAISON"
        isAllCaps = false
        minHeight = minimumTouchTargetPx()
        minimumHeight = minimumTouchTargetPx()
        contentDescription = "Ouvrir le mini-jeu Objectif livraison"
        setOnClickListener { context.startActivity(Intent(context, ObjectiveDeliveryGameActivity::class.java)) }
    }
    private fun minimumTouchTargetPx(): Int =
        kotlin.math.ceil(48 * resources.displayMetrics.density.toDouble()).toInt()
}
