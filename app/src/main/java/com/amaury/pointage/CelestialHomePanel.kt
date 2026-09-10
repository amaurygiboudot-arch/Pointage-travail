package com.amaury.pointage

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.min

/**
 * Conteneur responsive de l'accueil céleste.
 *
 * L'horloge doit devenir l'élément principal de l'écran Accueil sans dépendre
 * d'une taille fixe en dp. La largeur disponible pilote donc le diamètre, avec
 * une limite liée à la hauteur réelle de l'écran pour rester confortable sur
 * téléphone comme sur tablette.
 */
class CelestialHomePanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = View.MeasureSpec.getMode(widthMeasureSpec)
        val availableWidth = View.MeasureSpec.getSize(widthMeasureSpec)
        if (widthMode == View.MeasureSpec.UNSPECIFIED || availableWidth <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }

        val density = resources.displayMetrics.density
        val screenHeight = resources.displayMetrics.heightPixels
        val minHeight = (260f * density).toInt()
        val maxHeight = min((520f * density).toInt(), (screenHeight * 0.62f).toInt())
        val targetHeight = availableWidth.coerceIn(minHeight, maxHeight.coerceAtLeast(minHeight))

        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
        )
    }
}
