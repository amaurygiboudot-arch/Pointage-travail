package com.amaury.pointage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * Règle commune pour toutes les images fournies pour les boutons HP Travail.
 *
 * L'image source est toujours utilisée telle qu'elle a été importée :
 * - aucun filtre de couleur ;
 * - aucune modification de luminosité/contraste/saturation ;
 * - aucune transparence fabriquée ;
 * - aucun traitement des pixels ;
 * - aucune découpe de la source.
 *
 * Seule une mise à l'échelle d'affichage vers la zone cible est autorisée.
 * Les masques de forme (cadre, capsule, etc.) sont appliqués au Canvas par le
 * composant appelant et ne modifient jamais le Bitmap original.
 */
object OriginalButtonImageRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        alpha = 255
        colorFilter = null
    }

    fun draw(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || target.width() <= 0f || target.height() <= 0f) return

        paint.alpha = 255
        paint.colorFilter = null
        paint.shader = null

        val source = Rect(0, 0, bitmap.width, bitmap.height)
        canvas.drawBitmap(bitmap, source, target, paint)
    }
}
