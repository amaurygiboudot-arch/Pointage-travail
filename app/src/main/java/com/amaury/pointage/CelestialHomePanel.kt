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
 *
 * Le panneau est légèrement plus haut que large : le cadran reste circulaire,
 * mais Soleil/Lune disposent de la marge verticale nécessaire près de l'horizon
 * sans être rognés par le bas du conteneur.
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
        val minHeight = (280f * density).toInt()
        val maxHeight = min((540f * density).toInt(), (screenHeight * 0.64f).toInt())
        val desiredHeight = (availableWidth * 1.14f).toInt()
        val targetHeight = desiredHeight.coerceIn(minHeight, maxHeight.coerceAtLeast(minHeight))

        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY)
        )
    }
}
