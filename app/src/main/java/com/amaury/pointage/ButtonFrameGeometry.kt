package com.amaury.pointage

import android.graphics.Path
import android.graphics.RectF

/**
 * Géométrie commune des cadres de boutons HP Travail.
 *
 * Règle : un cadre est une matière en forme de couronne.
 * Le centre n'est jamais une couche transparente du cadre : il n'est pas dessiné.
 * Toute texture intérieure appartient donc exclusivement au remplissage du bouton.
 *
 * Les thèmes présents et futurs qui utilisent une image/texture de cadre doivent
 * clipper cette image avec le Path retourné par [buildBand].
 */
object ButtonFrameGeometry {
    fun buildBand(
        target: RectF,
        outerRadius: Float,
        thickness: Float,
        outPath: Path,
        innerPath: Path
    ): RectF? {
        val band = thickness.coerceAtLeast(0f)
        val inner = RectF(
            target.left + band,
            target.top + band,
            target.right - band,
            target.bottom - band
        )

        if (inner.width() <= 0f || inner.height() <= 0f) return null

        outPath.reset()
        outPath.addRoundRect(target, outerRadius, outerRadius, Path.Direction.CW)

        val innerRadius = (outerRadius - band).coerceAtLeast(1f)
        innerPath.reset()
        innerPath.addRoundRect(inner, innerRadius, innerRadius, Path.Direction.CW)

        outPath.op(innerPath, Path.Op.DIFFERENCE)
        return inner
    }
}
