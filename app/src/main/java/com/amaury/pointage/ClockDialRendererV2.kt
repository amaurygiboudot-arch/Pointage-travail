package com.amaury.pointage

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Cadran natif haute définition commun à l'horloge Accueil et au widget Android.
 *
 * Aucun bitmap n'est utilisé : anneaux, graduations, chiffres et repères sont
 * recalculés à la résolution réelle du Canvas. Le rendu reste donc net sur
 * téléphone, tablette, multi-fenêtre et densités d'écran différentes.
 */
object ClockDialRendererV2 {
    private val goldStops = intArrayOf(
        Color.rgb(92, 48, 5),
        Color.rgb(255, 224, 115),
        Color.rgb(171, 92, 8),
        Color.rgb(255, 246, 181),
        Color.rgb(111, 57, 4),
        Color.rgb(245, 190, 54),
        Color.rgb(92, 48, 5)
    )
    private val blueStops = intArrayOf(
        Color.rgb(0, 8, 29),
        Color.rgb(3, 54, 135),
        Color.rgb(1, 18, 58),
        Color.rgb(7, 90, 189),
        Color.rgb(0, 15, 48),
        Color.rgb(3, 48, 118),
        Color.rgb(0, 8, 29)
    )

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isDither = true
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        isDither = true
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        isDither = true
    }

    @Synchronized
    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        if (radius <= 2f) return

        // Fond : noir profond, indépendant du thème pour conserver le contraste.
        fill.shader = null
        fill.color = Color.rgb(2, 4, 8)
        canvas.drawCircle(cx, cy, radius * 0.985f, fill)

        // Bague or extérieure métallique.
        stroke.shader = SweepGradient(cx, cy, goldStops, null)
        stroke.strokeWidth = max(1.4f, radius * 0.055f)
        canvas.drawCircle(cx, cy, radius * 0.955f, stroke)

        // Filet lumineux extérieur : très fin, donc net même sur petit écran.
        stroke.shader = null
        stroke.color = Color.rgb(255, 236, 157)
        stroke.strokeWidth = max(0.75f, radius * 0.010f)
        canvas.drawCircle(cx, cy, radius * 0.982f, stroke)

        // Bague bleue centrale avec profondeur métallique.
        stroke.shader = SweepGradient(cx, cy, blueStops, null)
        stroke.strokeWidth = max(1.4f, radius * 0.090f)
        canvas.drawCircle(cx, cy, radius * 0.875f, stroke)

        // Liserés or autour de la bague bleue.
        stroke.shader = null
        stroke.color = Color.rgb(239, 177, 43)
        stroke.strokeWidth = max(1.0f, radius * 0.018f)
        canvas.drawCircle(cx, cy, radius * 0.817f, stroke)
        canvas.drawCircle(cx, cy, radius * 0.927f, stroke)

        stroke.color = Color.rgb(255, 242, 166)
        stroke.strokeWidth = max(0.65f, radius * 0.0075f)
        canvas.drawCircle(cx, cy, radius * 0.805f, stroke)
        canvas.drawCircle(cx, cy, radius * 0.940f, stroke)

        // Graduations : 60 traits recalculés à la vraie résolution du Canvas.
        for (tick in 0 until 60) {
            val angle = Math.toRadians(tick * 6.0 - 90.0)
            val isHour = tick % 5 == 0
            val outer = radius * 0.905f
            val inner = radius * if (isHour) 0.842f else 0.866f
            val x1 = cx + cos(angle).toFloat() * inner
            val y1 = cy + sin(angle).toFloat() * inner
            val x2 = cx + cos(angle).toFloat() * outer
            val y2 = cy + sin(angle).toFloat() * outer
            stroke.shader = null
            stroke.color = if (isHour) Color.rgb(255, 210, 80) else Color.rgb(179, 126, 35)
            stroke.strokeWidth = max(
                if (isHour) 1.25f else 0.65f,
                radius * if (isHour) 0.014f else 0.0065f
            )
            canvas.drawLine(x1, y1, x2, y2, stroke)
        }

        // Chiffres horaires.
        text.color = Color.rgb(246, 197, 76)
        text.textSize = max(11f, radius * 0.118f)
        text.setShadowLayer(max(0.8f, radius * 0.009f), 0f, 0f, Color.rgb(75, 43, 4))
        val numeralRadius = radius * 0.735f
        val metrics = text.fontMetrics
        val baselineOffset = -(metrics.ascent + metrics.descent) * 0.5f
        for (hour in 1..12) {
            val angle = Math.toRadians(hour * 30.0 - 90.0)
            val x = cx + cos(angle).toFloat() * numeralRadius
            val y = cy + sin(angle).toFloat() * numeralRadius + baselineOffset
            canvas.drawText(hour.toString(), x, y, text)
        }
        text.clearShadowLayer()

        // Quatre repères principaux en triangle, eux aussi vectoriels.
        for (hourIndex in 0 until 4) {
            val angleDeg = hourIndex * 90f - 90f
            drawCardinalMarker(canvas, cx, cy, radius, angleDeg)
        }
    }

    private fun drawCardinalMarker(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        angleDeg: Float
    ) {
        canvas.save()
        canvas.rotate(angleDeg + 90f, cx, cy)
        val tipY = cy - radius * 0.985f
        val baseY = cy - radius * 0.925f
        val half = radius * 0.040f
        val marker = Path().apply {
            moveTo(cx, tipY)
            lineTo(cx - half, baseY)
            lineTo(cx + half, baseY)
            close()
        }
        fill.shader = null
        fill.color = Color.rgb(255, 239, 172)
        canvas.drawPath(marker, fill)
        stroke.shader = null
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = max(0.8f, radius * 0.007f)
        stroke.color = Color.rgb(188, 122, 18)
        canvas.drawPath(marker, stroke)
        canvas.restore()
    }
}
