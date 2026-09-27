package com.amaury.pointage

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/** Point d'entrée public du mini-jeu Objectif livraison dans Aide & extras. */
class ObjectiveDeliveryGameButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    init {
        text = "JOUER À OBJECTIF LIVRAISON"
        isAllCaps = false
        contentDescription = "Ouvrir le jeu Objectif livraison"
        setOnClickListener {
            context.startActivity(Intent(context, ObjectiveDeliveryGameActivity::class.java))
        }
    }
}
