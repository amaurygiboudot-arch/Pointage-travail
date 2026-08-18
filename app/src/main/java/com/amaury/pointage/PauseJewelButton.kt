package com.amaury.pointage

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class PauseJewelButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : LightReactiveJewelButton(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val facetPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        contentDescription = "Pause"
        setOnClickListener {
            if (!PointageStore.hasOpen(context)) {
                Toast.makeText(context, "Commence d'abord une entrée", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val paused = PointageStore.isPaused(context)
            val ok = if (paused) PointageStore.resumePause(context) else PointageStore.startPause(context)
            val message = when {
                ok && paused -> "Pause terminée — travail repris"
                ok -> "Pause démarrée"
                else -> "Impossible de modifier la pause"
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            (context as? Activity)?.recreate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val d = resources.displayMetrics.density
        val r = min(w, h) / 2f - 2f * d
        val cx = w / 2f; val cy = h / 2f
        val rad = Math.toRadians(jewelLightAngle.toDouble())
        val lx = cx + (cos(rad) * r * 0.40).toFloat()
        val ly = cy + (sin(rad) * r * 0.40).toFloat()

        paint.shader = LinearGradient(
            cx - r, cy - r, cx + r, cy + r,
            intArrayOf(Color.parseColor("#FFB74D"), Color.parseColor("#F57C00"), Color.parseColor("#7A3D00")),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r - 7f * d, paint)
        paint.shader = null

        val pts = arrayOf(
            floatArrayOf(cx, cy - r * .72f), floatArrayOf(cx + r * .62f, cy - r * .20f),
            floatArrayOf(cx + r * .48f, cy + r * .56f), floatArrayOf(cx - r * .48f, cy + r * .56f),
            floatArrayOf(cx - r * .62f, cy - r * .20f)
        )
        for (i in pts.indices) {
            val p1 = pts[i]; val p2 = pts[(i + 1) % pts.size]
            val path = Path().apply { moveTo(cx, cy); lineTo(p1[0], p1[1]); lineTo(p2[0], p2[1]); close() }
            facetPaint.color = if (i % 2 == 0) Color.argb(35, 255, 255, 255) else Color.argb(26, 60, 20, 0)
            canvas.drawPath(path, facetPaint)
        }

        paint.shader = RadialGradient(
            lx, ly, r * .68f,
            intArrayOf(Color.argb(if (isPressed) 115 else 205, 255, 250, 225), Color.argb(92, 255, 185, 80), Color.TRANSPARENT),
            floatArrayOf(0f, .34f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r - 7f * d, paint)
        paint.shader = null

        val diamondRadius = r - 3.7f * d
        for (i in 0 until 18) {
            val a = Math.toRadians((i * 20.0) + jewelLightAngle)
            val x = cx + cos(a).toFloat() * diamondRadius
            val y = cy + sin(a).toFloat() * diamondRadius
            val facing = ((cos(Math.toRadians(i * 20.0 - jewelLightAngle.toDouble())) + 1.0) / 2.0).toFloat()
            paint.color = Color.argb((75 + 175 * facing).toInt().coerceIn(0,255), 255,255,255)
            canvas.drawCircle(x, y, (1.4f + 1.2f * facing) * d, paint)
        }

        ringPaint.strokeWidth = 3.2f * d
        ringPaint.color = jewelAccent
        canvas.drawCircle(cx, cy, r - 1.5f * d, ringPaint)
        ringPaint.strokeWidth = 1.1f * d
        ringPaint.color = jewelAccentLight
        canvas.drawCircle(cx, cy, r - 7.0f * d, ringPaint)

        paint.color = Color.argb(230, 35, 20, 8)
        val barW = r * .13f; val barH = r * .40f
        canvas.drawRoundRect(RectF(cx - barW * 1.8f, cy - barH, cx - barW * .55f, cy + barH), 3f*d, 3f*d, paint)
        canvas.drawRoundRect(RectF(cx + barW * .55f, cy - barH, cx + barW * 1.8f, cy + barH), 3f*d, 3f*d, paint)
    }
}
