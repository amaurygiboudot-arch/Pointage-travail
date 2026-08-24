package com.amaury.pointage

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Base de diagnostic du thème Carbone.
 *
 * Aucun cadre, aucun fond, aucune texture et aucun effet visuel ne sont dessinés.
 * Le Button Android conserve uniquement son texte et son comportement de clic.
 * Cette classe reste volontairement en place pour ne pas modifier le câblage des boutons
 * ni les autres thèmes pendant la reconstruction visuelle du thème Carbone.
 */
class CarbonCompositeDrawable(@Suppress("UNUSED_PARAMETER") context: Context) : Drawable() {
    companion object {
        @JvmStatic
        fun updateGlobalLight(@Suppress("UNUSED_PARAMETER") angle: Float, @Suppress("UNUSED_PARAMETER") night: Boolean) {
            // Aucun effet lumineux dans la base nue.
        }
    }

    override fun draw(canvas: Canvas) {
        // Intentionnellement vide : texte + clic uniquement.
    }

    override fun setAlpha(alpha: Int) {
        // Aucun visuel à rendre.
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        // Aucun visuel à filtrer.
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSPARENT
}
