package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

/** Bouton public du mini-jeu Serpent, affiché en bas des Paramètres. */
class SnakeGameButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    init {
        text = "🐍  JOUER AU SERPENT"
        isAllCaps = false
        contentDescription = "Ouvrir le jeu Serpent"
        setOnClickListener { SnakeGameDialog.show(context) }
    }
}
