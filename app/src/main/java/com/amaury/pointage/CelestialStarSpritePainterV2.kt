package com.amaury.pointage

import android.graphics.*
import com.amaury.pointage.v2.engine.CelestialStarStyleV2

/** Reused soft sprite; no per-star bitmap allocation, blur or decorative spikes. */
internal class CelestialStarSpritePainterV2 {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = RadialGradient(0f, 0f, 1f,
        intArrayOf(Color.rgb(243, 247, 255), Color.argb(80, 243, 247, 255), Color.TRANSPARENT),
        floatArrayOf(0f, 0.30f, 1f), Shader.TileMode.CLAMP)
    private val matrix = Matrix()

    fun draw(canvas: Canvas, x: Float, y: Float, scale: Float, style: CelestialStarStyleV2) {
        if (style.coreAlpha <= 0.0 || scale <= 0f) return
        val radius = (style.radius * scale).toFloat()
        val haloRadius = (style.haloRadius * scale).toFloat()
        if (style.haloAlpha > 0.0) {
            matrix.setScale(haloRadius, haloRadius)
            matrix.postTranslate(x, y)
            halo.setLocalMatrix(matrix)
            paint.shader = halo
            paint.alpha = (255 * style.haloAlpha).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, haloRadius, paint)
        }
        paint.shader = null
        paint.color = Color.rgb(243, 247, 255)
        paint.alpha = (255 * style.coreAlpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, radius, paint)
        paint.color = Color.WHITE
        paint.alpha = (230 * style.coreAlpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(x, y, radius * 0.4f, paint)
    }
}
